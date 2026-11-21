package com.wherelee.base.modules.identity.session;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.wherelee.base.modules.identity.security.JwtProperties;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 登录会话：签发 token 时落库并写 Redis 活跃标记；吊销时删标记，多实例即时生效。
 *
 * <p>JWT 保持无状态风格：sid 只是「可吊销的会话 id」，每次请求用 Redis（O(1)）判断是否活跃；
 * Redis 不可用时回落到 DB 查询（正确性优先）。没有 sid 的旧 token 跳过会话校验。</p>
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final String ACTIVE_KEY = "atry:session:active:";
    private static final RowMapper<SessionView> MAPPER = (rs, rowNum) -> new SessionView(
            rs.getString("session_id"),
            rs.getString("ip"),
            rs.getString("user_agent"),
            rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
            false);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final JwtProperties jwtProperties;
    private final int retentionDays;

    public SessionService(JdbcTemplate jdbc, StringRedisTemplate redis, JwtProperties jwtProperties,
                          @org.springframework.beans.factory.annotation.Value("${app.auth.session-retention-days:30}")
                          int retentionDays) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.jwtProperties = jwtProperties;
        this.retentionDays = Math.max(1, retentionDays);
    }

    /** 创建会话并返回 sid；Redis 写失败不影响登录（靠 DB 兜底）。 */
    public String create(long tenantId, long userId, String ip, String userAgent) {
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofMinutes(Math.max(1, jwtProperties.ttlMinutes())));
        jdbc.update("""
                INSERT INTO t_user_session
                    (tenant_id, session_id, user_id, ip, user_agent, last_seen_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, tenantId, sessionId, userId, abbreviate(ip, 64), abbreviate(userAgent, 255),
                Timestamp.from(now), Timestamp.from(expiresAt));
        cacheActive(tenantId, sessionId, Duration.between(now, expiresAt));
        return sessionId;
    }

    /** 会话是否仍活跃；Redis 判断失败时回落 DB。 */
    public boolean isActive(long tenantId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return true;
        }
        try {
            if (Boolean.TRUE.equals(redis.hasKey(key(sessionId)))) {
                return true;
            }
        } catch (RuntimeException e) {
            log.warn("会话缓存读取失败，回落数据库 sessionId={} cause={}", sessionId, e.getClass().getSimpleName());
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_user_session
                WHERE tenant_id = ? AND session_id = ? AND revoked = 0 AND expires_at > CURRENT_TIMESTAMP(3)
                """, Integer.class, tenantId, sessionId);
        boolean active = count != null && count > 0;
        if (active) {
            // 缓存短 TTL：即使这次回填，后续吊销仍能在数分钟内被感知
            cacheActive(tenantId, sessionId, Duration.ofMinutes(5));
        }
        return active;
    }

    public List<SessionView> list(long tenantId, long userId, String currentSessionId) {
        List<SessionView> sessions = jdbc.query("""
                SELECT session_id, ip, user_agent, created_at, expires_at
                FROM t_user_session
                WHERE tenant_id = ? AND user_id = ? AND revoked = 0
                  AND expires_at > CURRENT_TIMESTAMP(3)
                ORDER BY created_at DESC
                LIMIT 50
                """, MAPPER, tenantId, userId);
        return sessions.stream()
                .map(session -> new SessionView(session.sessionId(), session.ip(), session.userAgent(),
                        session.createdAt(), session.expiresAt(),
                        session.sessionId().equals(currentSessionId)))
                .toList();
    }

    /** 吊销指定会话（只能操作自己的）；返回是否实际吊销。 */
    public boolean revoke(long tenantId, long userId, String sessionId, String reason) {
        int updated = jdbc.update("""
                UPDATE t_user_session
                SET revoked = 1, revoked_at = CURRENT_TIMESTAMP(3), revoked_reason = ?
                WHERE tenant_id = ? AND user_id = ? AND session_id = ? AND revoked = 0
                """, abbreviate(reason, 40), tenantId, userId, sessionId);
        if (updated > 0) {
            try {
                redis.delete(key(sessionId));
            } catch (RuntimeException e) {
                log.warn("会话缓存删除失败 sessionId={} cause={}", sessionId, e.getClass().getSimpleName());
            }
        }
        return updated > 0;
    }

    /** 每日清理：过期或已吊销超过保留期的会话行。 */
    @Scheduled(cron = "${app.auth.session-cleanup-cron:0 30 3 * * *}")
    public void cleanupJob() {
        try {
            int deleted = cleanup(retentionDays);
            if (deleted > 0) {
                log.info("会话清理完成 deleted={}", deleted);
            }
        } catch (RuntimeException e) {
            log.warn("会话清理失败 cause={}", e.getClass().getSimpleName());
        }
    }

    public int cleanup(int retentionDays) {
        return TenantContext.runAsSystem(() -> jdbc.update("""
                DELETE FROM t_user_session
                WHERE (revoked = 1 AND revoked_at < DATE_SUB(NOW(), INTERVAL ? DAY))
                   OR (revoked = 0 AND expires_at < DATE_SUB(NOW(), INTERVAL ? DAY))
                """, retentionDays, retentionDays));
    }

    private void cacheActive(long tenantId, String sessionId, Duration ttl) {
        try {
            redis.opsForValue().set(key(sessionId), String.valueOf(tenantId), ttl);
        } catch (RuntimeException e) {
            log.warn("会话缓存写入失败 sessionId={} cause={}", sessionId, e.getClass().getSimpleName());
        }
    }

    private String key(String sessionId) {
        return ACTIVE_KEY + sessionId;
    }

    private String abbreviate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
