package com.wherelee.base.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理员运营端集成测试：用户管理、角色权限、审计、幂等与权限缓存失效。
 *
 * <p>覆盖业务不变式：管理员不能改自己状态/重置自己密码；建号必须角色存在且首登改密；
 * 管理操作必须落审计（含操作人与目标）；角色授权后权限缓存立即失效（无需重启/等待 TTL）；
 * 相同幂等键的重复提交返回 409（@Idempotent 切面 + Redis）。</p>
 */
class AdminGovernanceIntegrationTest extends IntegrationTestSupport {

    private static final String PASSWORD = "Admin1234";

    private String adminToken(long tenant, long adminId) {
        return tokenFor(tenant, adminId, "gov_admin", "GOV_ADMIN");
    }

    private long seedAdmin(long tenant) {
        seedRole(tenant, "GOV_ADMIN", "admin:user:manage", "admin:role:manage");
        return seedUser(tenant, "gov_admin", PASSWORD, "GOV_ADMIN");
    }

    private long createUser(String adminToken, String username, String role) throws Exception {
        String body = mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "nickname", "新用户",
                                "initialPassword", "InitPass123", "role", role))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.trim());
    }

    @Test
    void 管理员建号_首登改密_禁用与重置密码_审计落库() throws Exception {
        long tenant = newTenant();
        seedRole(tenant, "TARGET_ROLE");
        long adminId = seedAdmin(tenant);
        String adminToken = adminToken(tenant, adminId);

        long userId = createUser(adminToken, "created_user", "TARGET_ROLE");
        assertThat(userId).isPositive();
        // 首登必须改密
        assertThat(queryOne("SELECT must_change_password FROM t_user WHERE id = ? AND tenant_id = ?",
                Boolean.class, userId, tenant)).isTrue();
        // 初始密码可登录
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "created_user", "password", "InitPass123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));

        // 重复创建同用户名 -> 400
        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "created_user", "nickname", "重复",
                                "initialPassword", "InitPass123", "role", "TARGET_ROLE"))))
                .andExpect(status().isBadRequest());

        // 不能修改自己的状态
        mockMvc.perform(put("/api/admin/users/" + adminId + "/status")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "DISABLED"))))
                .andExpect(status().isBadRequest());

        // 禁用目标用户 -> 登录失败
        mockMvc.perform(put("/api/admin/users/" + userId + "/status")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "DISABLED"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "created_user", "password", "InitPass123"))))
                .andExpect(status().isUnauthorized());

        // 重置密码（会递增 token_version 并要求再次改密）
        mockMvc.perform(put("/api/admin/users/" + userId + "/password")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "newPassword", "ResetPass123"))))
                .andExpect(status().isOk());
        assertThat(queryOne("SELECT must_change_password FROM t_user WHERE id = ?", Boolean.class, userId))
                .isTrue();

        // 审计：操作人、动作、目标、成功状态
        List<String> audits = queryAudit(tenant, adminId);
        assertThat(audits).anySatisfy(row -> assertThat(row)
                .contains("USER_CREATE").contains("|" + userId + "|"));
        assertThat(audits).anySatisfy(row -> assertThat(row).contains("USER_STATUS_CHANGE"));
        assertThat(audits).anySatisfy(row -> assertThat(row).contains("USER_PASSWORD_RESET"));
    }

    private List<String> queryAudit(long tenant, long operatorId) {
        return asSystem(() -> jdbc.queryForList(
                "SELECT CONCAT(action, '|', IFNULL(target_id,'NULL'), '|', success) "
                        + "FROM t_admin_audit_log WHERE tenant_id = ? AND operator_id = ? ORDER BY id",
                String.class, tenant, operatorId));
    }

    @Test
    void 角色管理与权限缓存失效端到端() throws Exception {
        long tenant = newTenant();
        long adminId = seedAdmin(tenant);
        String adminToken = adminToken(tenant, adminId);
        seedRole(tenant, "CACHE_ROLE");
        long targetId = seedUser(tenant, "cache_user", PASSWORD, "CACHE_ROLE");
        String targetToken = tokenFor(tenant, targetId, "cache_user", "CACHE_ROLE");

        // 授权前：无权限 403（权限解析会缓存空结果）
        mockMvc.perform(get("/api/admin/users").header("Authorization", bearer(targetToken)))
                .andExpect(status().isForbidden());

        // 管理员给角色授予 admin:user:manage
        mockMvc.perform(put("/api/admin/roles/CACHE_ROLE/permissions")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "permissions", List.of("admin:user:manage")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions[0]").value("admin:user:manage"));

        // 缓存必须立即失效：同一 token 下一次请求即可通过
        mockMvc.perform(get("/api/admin/users").header("Authorization", bearer(targetToken)))
                .andExpect(status().isOk());
    }

    @Test
    void 创建角色并与内置角色同码冲突返回409() throws Exception {
        long tenant = newTenant();
        long adminId = seedAdmin(tenant);
        String adminToken = adminToken(tenant, adminId);
        mockMvc.perform(post("/api/admin/roles")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "GOV_ADMIN", "name", "重复角色",
                                "description", "x", "permissions", List.of()))))
                .andExpect(status().isConflict());
    }

    @Test
    void 用户列表分页与关键字过滤() throws Exception {
        long tenant = newTenant();
        long adminId = seedAdmin(tenant);
        String adminToken = adminToken(tenant, adminId);
        seedRole(tenant, "LIST_ROLE");
        for (int i = 0; i < 3; i++) {
            seedUser(tenant, "list_user_" + i, PASSWORD, "LIST_ROLE");
        }

        mockMvc.perform(get("/api/admin/users?keyword=list_user&page=1&size=2")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.records.length()").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2));
    }

    @Test
    void 幂等切面拦截重复提交返回409() throws Exception {
        long tenant = newTenant();
        long adminId = seedAdmin(tenant);
        String adminToken = adminToken(tenant, adminId);
        seedRole(tenant, "IDEMP_ROLE");
        String body = objectMapper.writeValueAsString(Map.of(
                "username", "idemp_user", "nickname", "幂等",
                "initialPassword", "InitPass123", "role", "IDEMP_ROLE"));

        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        // 同一请求 5 秒窗口内重复提交：Idempotent 切面返回 409
        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void 无权限用户不能访问运营端() throws Exception {
        long tenant = newTenant();
        seedRole(tenant, "NO_PERM");
        long userId = seedUser(tenant, "no_perm_user", PASSWORD, "NO_PERM");
        String token = tokenFor(tenant, userId, "no_perm_user", "NO_PERM");

        mockMvc.perform(get("/api/admin/users").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/roles")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "HACK", "name", "越权", "permissions", List.of()))))
                .andExpect(status().isForbidden());
    }
}
