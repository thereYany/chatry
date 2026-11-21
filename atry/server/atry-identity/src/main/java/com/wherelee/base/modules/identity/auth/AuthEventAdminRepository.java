package com.wherelee.base.modules.identity.auth;

import com.wherelee.base.modules.platform.common.PageResult;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 认证事件检索：按当前租户过滤，事件类型/用户名/结果/时间可组合。 */
@Repository
public class AuthEventAdminRepository {

    private static final RowMapper<AuthEventView> MAPPER = (rs, rowNum) -> new AuthEventView(
            rs.getLong("id"),
            (Long) rs.getObject("user_id"),
            rs.getString("username"),
            rs.getString("event_type"),
            rs.getBoolean("success"),
            rs.getString("ip"),
            rs.getString("user_agent"),
            rs.getString("detail"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public AuthEventAdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PageResult<AuthEventView> search(String eventType, String username, Boolean success,
                                            LocalDateTime from, LocalDateTime to,
                                            int page, int size) {
        List<Object> args = new ArrayList<>();
        String where = where(eventType, username, success, from, to, args);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM t_auth_event " + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<AuthEventView> records = jdbc.query("""
                SELECT id, user_id, username, event_type, success, ip, user_agent, detail, created_at
                FROM t_auth_event
                """ + where + " ORDER BY id DESC LIMIT ? OFFSET ?", MAPPER, pageArgs.toArray());
        return new PageResult<>(records, total == null ? 0 : total, page, size);
    }

    private String where(String eventType, String username, Boolean success,
                         LocalDateTime from, LocalDateTime to, List<Object> args) {
        List<String> conditions = new ArrayList<>();
        conditions.add("tenant_id = ?");
        args.add(TenantContext.current());
        if (eventType != null && !eventType.isBlank()) {
            conditions.add("event_type = ?");
            args.add(eventType.trim());
        }
        if (username != null && !username.isBlank()) {
            conditions.add("username LIKE ?");
            args.add("%" + username.trim() + "%");
        }
        if (success != null) {
            conditions.add("success = ?");
            args.add(success);
        }
        if (from != null) {
            conditions.add("created_at >= ?");
            args.add(Timestamp.valueOf(from));
        }
        if (to != null) {
            conditions.add("created_at < ?");
            args.add(Timestamp.valueOf(to));
        }
        return " WHERE " + String.join(" AND ", conditions);
    }
}
