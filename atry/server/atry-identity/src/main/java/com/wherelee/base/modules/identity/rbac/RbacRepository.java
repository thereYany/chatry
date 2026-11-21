package com.wherelee.base.modules.identity.rbac;

import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 角色/权限仓储（t_role / t_permission / t_role_permission / t_user_role）。
 *
 * <p>多表查询显式带 tenant_id：租户拦截器对跨表 SQL 不做自动注入，必须自己写全。</p>
 */
@Repository
public class RbacRepository {

    private final JdbcTemplate jdbc;

    public RbacRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Long> findRoleId(String roleCode) {
        return jdbc.query("SELECT id FROM t_role WHERE tenant_id = ? AND code = ?",
                (rs, rowNum) -> rs.getLong("id"), TenantContext.current(), roleCode)
                .stream().findFirst();
    }

    public List<String> findRoleCodesByUser(Long userId) {
        return jdbc.query("""
                SELECT r.code
                FROM t_user_role ur
                         JOIN t_role r ON r.id = ur.role_id AND r.tenant_id = ur.tenant_id
                WHERE ur.tenant_id = ? AND ur.user_id = ?
                ORDER BY r.code
                """, (rs, rowNum) -> rs.getString("code"), TenantContext.current(), userId);
    }

    /** 用户拥有的权限码（多角色合并、去重）。 */
    public List<String> findPermissionCodesByUser(Long userId) {
        return jdbc.query("""
                SELECT DISTINCT p.code
                FROM t_user_role ur
                         JOIN t_role_permission rp ON rp.role_id = ur.role_id AND rp.tenant_id = ur.tenant_id
                         JOIN t_permission p ON p.id = rp.permission_id AND p.tenant_id = rp.tenant_id
                WHERE ur.tenant_id = ? AND ur.user_id = ?
                """, (rs, rowNum) -> rs.getString("code"), TenantContext.current(), userId);
    }

    public List<RoleRow> listRoles() {
        return jdbc.query("""
                SELECT id, code, name, description, built_in
                FROM t_role WHERE tenant_id = ? ORDER BY built_in DESC, code
                """, (rs, rowNum) -> new RoleRow(rs.getLong("id"), rs.getString("code"),
                rs.getString("name"), rs.getString("description"), rs.getBoolean("built_in")),
                TenantContext.current());
    }

    public List<String> findPermissionCodesByRole(Long roleId) {
        return jdbc.query("""
                SELECT p.code
                FROM t_role_permission rp
                         JOIN t_permission p ON p.id = rp.permission_id AND p.tenant_id = rp.tenant_id
                WHERE rp.tenant_id = ? AND rp.role_id = ?
                ORDER BY p.code
                """, (rs, rowNum) -> rs.getString("code"), TenantContext.current(), roleId);
    }

    public List<PermissionRow> listPermissions() {
        return jdbc.query("""
                SELECT code, name, description FROM t_permission WHERE tenant_id = ? ORDER BY code
                """, (rs, rowNum) -> new PermissionRow(rs.getString("code"), rs.getString("name"),
                rs.getString("description")), TenantContext.current());
    }

    public Long insertRole(String code, String name, String description) {
        jdbc.update("INSERT INTO t_role (tenant_id, code, name, description, built_in) VALUES (?, ?, ?, ?, 0)",
                TenantContext.current(), code, name, description);
        return jdbc.queryForObject("SELECT id FROM t_role WHERE tenant_id = ? AND code = ?",
                Long.class, TenantContext.current(), code);
    }

    public void replaceRolePermissions(Long roleId, List<String> permissionCodes) {
        Long tenant = TenantContext.current();
        jdbc.update("DELETE FROM t_role_permission WHERE tenant_id = ? AND role_id = ?", tenant, roleId);
        for (String code : permissionCodes) {
            Long permissionId = jdbc.query("""
                    SELECT id FROM t_permission WHERE tenant_id = ? AND code = ?
                    """, (rs, rowNum) -> rs.getLong("id"), tenant, code).stream().findFirst().orElse(null);
            if (permissionId == null) {
                throw new IllegalArgumentException("未知权限码：" + code);
            }
            jdbc.update("""
                    INSERT IGNORE INTO t_role_permission (tenant_id, role_id, permission_id)
                    VALUES (?, ?, ?)
                    """, tenant, roleId, permissionId);
        }
    }

    public void replaceUserRoles(Long userId, List<Long> roleIds) {
        Long tenant = TenantContext.current();
        jdbc.update("DELETE FROM t_user_role WHERE tenant_id = ? AND user_id = ?", tenant, userId);
        for (Long roleId : roleIds) {
            jdbc.update("""
                    INSERT IGNORE INTO t_user_role (tenant_id, user_id, role_id) VALUES (?, ?, ?)
                    """, tenant, userId, roleId);
        }
    }

    public List<Long> findRoleIdsByCodes(List<String> roleCodes) {
        return jdbc.query("""
                SELECT id FROM t_role WHERE tenant_id = ? AND code IN (%s)
                """.formatted(placeholders(roleCodes.size())),
                (rs, rowNum) -> rs.getLong("id"),
                args(TenantContext.current(), roleCodes)).stream().toList();
    }

    /** 绑定单个角色（创建用户/受邀注册后调用），角色不存在则忽略。 */
    public void bindRole(Long userId, String roleCode) {
        findRoleId(roleCode).ifPresent(roleId -> jdbc.update("""
                INSERT IGNORE INTO t_user_role (tenant_id, user_id, role_id) VALUES (?, ?, ?)
                """, TenantContext.current(), userId, roleId));
    }

    private static String placeholders(int size) {
        return String.join(",", java.util.Collections.nCopies(Math.max(1, size), "?"));
    }

    private static Object[] args(Object first, List<String> rest) {
        Object[] all = new Object[rest.size() + 1];
        all[0] = first;
        for (int i = 0; i < rest.size(); i++) {
            all[i + 1] = rest.get(i);
        }
        return all;
    }

    public record RoleRow(Long id, String code, String name, String description, boolean builtIn) {
    }

    public record PermissionRow(String code, String name, String description) {
    }
}
