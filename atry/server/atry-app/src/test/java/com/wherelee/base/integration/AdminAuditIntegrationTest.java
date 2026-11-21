package com.wherelee.base.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审计统一检索：管理操作审计 + 认证事件，权限与租户隔离。
 */
class AdminAuditIntegrationTest extends IntegrationTestSupport {

    private static final String ACTION = "IT_AUDIT_ACTION";
    private static final String USERNAME = "it_audit_user";

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM t_admin_audit_log WHERE tenant_id = 0 AND action = ?", ACTION);
        jdbc.update("DELETE FROM t_auth_event WHERE tenant_id = 0 AND username = ?", USERNAME);
        jdbc.update("DELETE FROM t_user_role WHERE tenant_id = 0 AND user_id IN "
                + "(SELECT id FROM t_user WHERE tenant_id = 0 AND username IN ('it_auditor', 'it_plain'))");
        jdbc.update("DELETE FROM t_user WHERE tenant_id = 0 AND username IN ('it_auditor', 'it_plain')");
        jdbc.update("DELETE FROM t_role_permission WHERE tenant_id = 0 AND role_id IN "
                + "(SELECT id FROM t_role WHERE tenant_id = 0 AND code = 'IT_AUDITOR')");
        jdbc.update("DELETE FROM t_role WHERE tenant_id = 0 AND code = 'IT_AUDITOR'");
    }

    @Test
    void searchAdminAuditAndAuthEventsWithPermissionAndTenantIsolation() throws Exception {
        seedRole(0, "IT_AUDITOR", "admin:audit:view");
        seedRole(0, "IT_PLAIN");
        long auditorId = seedUser(0, "it_auditor", "Passw0rd!", "IT_AUDITOR");
        long plainId = seedUser(0, "it_plain", "Passw0rd!", "IT_PLAIN");
        String auditorToken = tokenFor(0, auditorId, "it_auditor", "IT_AUDITOR");

        jdbc.update("INSERT INTO t_admin_audit_log (tenant_id, operator_id, action, target_type, "
                + "target_id, success, duration_ms, trace_id) VALUES (0, ?, ?, 'USER', 42, 1, 12, 'trace-a')",
                auditorId, ACTION);
        jdbc.update("INSERT INTO t_admin_audit_log (tenant_id, operator_id, action, target_type, "
                + "success, duration_ms) VALUES (1, 99, ?, 'USER', 1, 5)", ACTION);
        jdbc.update("INSERT INTO t_auth_event (tenant_id, user_id, username, event_type, success, ip, detail) "
                + "VALUES (0, ?, ?, 'LOGIN_SUCCESS', 1, '10.0.0.1', 'ok')", plainId, USERNAME);
        jdbc.update("INSERT INTO t_auth_event (tenant_id, user_id, username, event_type, success) "
                + "VALUES (1, 99, ?, 'LOGIN_SUCCESS', 1)", USERNAME);

        mockMvc.perform(get("/api/admin/audit-logs")
                        .param("action", ACTION)
                        .param("success", "true")
                        .header("Authorization", bearer(auditorToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.records[0].action").value(ACTION))
                .andExpect(jsonPath("$.records[0].operatorId").value(auditorId));

        mockMvc.perform(get("/api/admin/auth-events")
                        .param("username", "audit_user")
                        .param("eventType", "LOGIN_SUCCESS")
                        .header("Authorization", bearer(auditorToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.records[0].eventType").value("LOGIN_SUCCESS"))
                .andExpect(jsonPath("$.records[0].ip").value("10.0.0.1"));

        mockMvc.perform(get("/api/admin/audit-logs")
                        .header("Authorization", bearer(tokenFor(0, plainId, "it_plain", "IT_PLAIN"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/auth-events")
                        .header("Authorization", bearer(tokenFor(0, plainId, "it_plain", "IT_PLAIN"))))
                .andExpect(status().isForbidden());
    }
}
