package com.wherelee.base.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 自助注册端到端：注册 → token → me → 会话 → 登出吊销；唯一键/弱密码/审计。
 *
 * <p>全程走真实 MySQL + Redis（集成 profile），注册限流在本类里被放宽（限流本身另有测试）。</p>
 */
class RegistrationIntegrationTest extends IntegrationTestSupport {

    private static final String PASSWORD = "Passw0rd!";
    private final List<String> usernames = new ArrayList<>();
    private final List<String> emails = new ArrayList<>();

    @AfterEach
    void cleanupRegistered() {
        for (String username : usernames) {
            jdbc.update("DELETE FROM t_user_session WHERE tenant_id = 0 AND user_id IN "
                    + "(SELECT id FROM t_user WHERE tenant_id = 0 AND username = ?)", username);
            jdbc.update("DELETE FROM t_user_role WHERE tenant_id = 0 AND user_id IN "
                    + "(SELECT id FROM t_user WHERE tenant_id = 0 AND username = ?)", username);
            jdbc.update("DELETE FROM t_auth_event WHERE tenant_id = 0 AND username = ?", username);
            jdbc.update("DELETE FROM t_user WHERE tenant_id = 0 AND username = ?", username);
        }
        usernames.clear();
        emails.clear();
    }

    private MvcResult register(String username, String password, String nickname, String email)
            throws Exception {
        usernames.add(username);
        if (email != null) {
            emails.add(email);
        }
        return mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password,
                                "nickname", nickname,
                                "email", email == null ? "" : email))))
                .andReturn();
    }

    @Test
    void registerThenMeSessionsAndLogout() throws Exception {
        String username = unique("reg");
        MvcResult registered = register(username, PASSWORD, "注册用户", username + "@example.com");
        assertThat(registered.getResponse().getStatus()).isEqualTo(200);
        String token = objectMapper.readTree(registered.getResponse().getContentAsString())
                .get("token").asString();
        assertThat(token).isNotBlank();

        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username));

        mockMvc.perform(get("/api/auth/sessions").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].current").value(true));

        Integer registerEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_auth_event WHERE tenant_id = 0 AND username = ? "
                        + "AND event_type = 'REGISTER'", Integer.class, username);
        assertThat(registerEvents).isEqualTo(1);

        mockMvc.perform(post("/api/auth/logout").header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());

        Integer logoutEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_auth_event WHERE tenant_id = 0 AND username = ? "
                        + "AND event_type = 'LOGOUT'", Integer.class, username);
        assertThat(logoutEvents).isEqualTo(1);
    }

    @Test
    void duplicateUsernameAndEmailReturnStableCodes() throws Exception {
        String username = unique("dup");
        String email = username + "@example.com";
        assertThat(register(username, PASSWORD, "重复一", email).getResponse().getStatus()).isEqualTo(200);

        MvcResult sameName = register(username, PASSWORD, "重复二", username + "2@example.com");
        assertThat(sameName.getResponse().getStatus()).isEqualTo(409);
        assertThat(objectMapper.readTree(sameName.getResponse().getContentAsString())
                .get("code").asString()).isEqualTo("USERNAME_TAKEN");

        String other = unique("dup2");
        MvcResult sameEmail = register(other, PASSWORD, "重复三", email);
        assertThat(sameEmail.getResponse().getStatus()).isEqualTo(409);
        assertThat(objectMapper.readTree(sameEmail.getResponse().getContentAsString())
                .get("code").asString()).isEqualTo("EMAIL_TAKEN");
    }

    @Test
    void weakPasswordAndMalformedEmailAreRejectedWithFieldDetails() throws Exception {
        MvcResult weak = register(unique("weak"), "12345678", "弱密码", null);
        assertThat(weak.getResponse().getStatus()).isEqualTo(400);
        var weakBody = objectMapper.readTree(weak.getResponse().getContentAsString());
        assertThat(weakBody.get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(weakBody.get("details").get(0).get("field").asString()).isEqualTo("password");

        MvcResult badEmail = register(unique("bademail"), PASSWORD, "坏邮箱", "not-an-email");
        assertThat(badEmail.getResponse().getStatus()).isEqualTo(400);
        assertThat(objectMapper.readTree(badEmail.getResponse().getContentAsString())
                .get("details").get(0).get("field").asString()).isEqualTo("email");
    }
}
