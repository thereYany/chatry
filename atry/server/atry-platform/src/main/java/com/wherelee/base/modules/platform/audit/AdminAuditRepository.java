package com.wherelee.base.modules.platform.audit;

import com.wherelee.base.modules.platform.common.PageResult;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理操作审计：写入 + 运维检索。
 *
 * <p>写入显式带 tenant_id（此前依赖默认 0，多租户下会串号）；
 * 检索只查当前租户，条件全部参数化。</p>
 */
@Repository
public class AdminAuditRepository {

    private static final RowMapper<AdminAuditView> MAPPER = (rs, rowNum) -> new AdminAuditView(
            rs.getLong("id"),
            rs.getLong("operator_id"),
            rs.getString("action"),
            rs.getString("target_type"),
            (Long) rs.getObject("target_id"),
            rs.getBoolean("success"),
            rs.getLong("duration_ms"),
            rs.getString("error_message"),
            rs.getString("trace_id"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public AdminAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Long operatorId, String action, String targetType, Long targetId,
                       boolean success, long durationMs, String error, String traceId) {
        jdbc.update("""
                INSERT INTO t_admin_audit_log
                    (tenant_id, operator_id, action, target_type, target_id, success, duration_ms,
                     error_message, trace_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, TenantContext.current(), operatorId, action, targetType, targetId, success,
                durationMs, error, traceId);
    }

    public PageResult<AdminAuditView> search(String action, Long operatorId, Boolean success,
                                             LocalDateTime from, LocalDateTime to,
                                             int page, int size) {
        List<Object> args = new ArrayList<>();
        String where = where(action, operatorId, success, from, to, args);
        long total = count(where, args);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<AdminAuditView> records = jdbc.query("""
                SELECT id, operator_id, action, target_type, target_id, success, duration_ms,
                       error_message, trace_id, created_at
                FROM t_admin_audit_log
                """ + where + " ORDER BY id DESC LIMIT ? OFFSET ?", MAPPER, pageArgs.toArray());
        return new PageResult<>(records, total, page, size);
    }

    private long count(String where, List<Object> args) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM t_admin_audit_log " + where,
                Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    private String where(String action, Long operatorId, Boolean success,
                         LocalDateTime from, LocalDateTime to, List<Object> args) {
        List<String> conditions = new ArrayList<>();
        conditions.add("tenant_id = ?");
        args.add(TenantContext.current());
        if (action != null && !action.isBlank()) {
            conditions.add("action = ?");
            args.add(action.trim());
        }
        if (operatorId != null) {
            conditions.add("operator_id = ?");
            args.add(operatorId);
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
