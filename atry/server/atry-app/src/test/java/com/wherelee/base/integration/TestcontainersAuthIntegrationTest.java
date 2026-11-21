package com.wherelee.base.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testcontainers 版本：不依赖本机 MySQL/Redis，CI 或本地有 Docker 即可跑。
 *
 * <p>没有 Docker 时整类跳过（{@code disabledWithoutDocker}），不会拖垮普通集成套件。</p>
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class TestcontainersAuthIntegrationTest extends IntegrationTestSupport {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("atry_it")
            .withUsername("atry")
            .withPassword("atrypass");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.database", () -> 0);
    }

    private String username;

    @AfterEach
    void cleanupRegistered() {
        if (username != null) {
            jdbc.update("DELETE FROM t_user_session WHERE tenant_id = 0 AND user_id IN "
                    + "(SELECT id FROM t_user WHERE tenant_id = 0 AND username = ?)", username);
            jdbc.update("DELETE FROM t_user_role WHERE tenant_id = 0 AND user_id IN "
                    + "(SELECT id FROM t_user WHERE tenant_id = 0 AND username = ?)", username);
            jdbc.update("DELETE FROM t_auth_event WHERE tenant_id = 0 AND username = ?", username);
            jdbc.update("DELETE FROM t_user WHERE tenant_id = 0 AND username = ?", username);
        }
    }

    @Test
    void registerAndMeWorksOnEphemeralContainers() throws Exception {
        username = "tc" + System.nanoTime();
        var result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", "Passw0rd!",
                                "nickname", "容器用户",
                                "email", username + "@example.com"))))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("token").asString();
        assertThat(token).isNotBlank();

        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username));
    }
}
