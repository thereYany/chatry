package com.wherelee.base.modules.identity.auth;

import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 认证事件仓储：只追加，不更新；密码/令牌等敏感值禁止写入。 */
@Repository
public class AuthEventRepository {

    private final JdbcTemplate jdbc;

    public AuthEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Long userId, String username, String eventType, boolean success,
                       String ip, String userAgent, String detail) {
        jdbc.update("""
                INSERT INTO t_auth_event
                    (tenant_id, user_id, username, event_type, success, ip, user_agent, detail)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                TenantContext.current(), userId, username, eventType, success,
                abbreviate(ip, 64), abbreviate(userAgent, 255), abbreviate(detail, 255));
    }

    private String abbreviate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
