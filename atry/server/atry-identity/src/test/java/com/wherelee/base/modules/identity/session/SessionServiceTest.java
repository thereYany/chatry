package com.wherelee.base.modules.identity.session;

import com.wherelee.base.modules.identity.security.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private SessionService service;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOperations);
        JwtProperties properties = new JwtProperties("unit-test-jwt-secret-0123456789-abcdef",
                60, "atry", "atry-web");
        service = new SessionService(jdbc, redis, properties, 30);
    }

    @Test
    void createPersistsAndCachesActiveSession() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        String sessionId = service.create(0L, 7L, "10.0.0.1", "JUnit");

        assertThat(sessionId).hasSize(32);
        verify(valueOperations).set(eq("atry:session:active:" + sessionId), eq("0"), any(Duration.class));
    }

    @Test
    void isActiveUsesRedisFastPath() {
        when(redis.hasKey("atry:session:active:sid-1")).thenReturn(true);

        assertThat(service.isActive(0L, "sid-1")).isTrue();
    }

    @Test
    void isActiveFallsBackToDatabaseWhenCacheMiss() {
        when(redis.hasKey("atry:session:active:sid-2")).thenReturn(false);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(0L), eq("sid-2"))).thenReturn(1);

        assertThat(service.isActive(0L, "sid-2")).isTrue();
        verify(valueOperations).set(eq("atry:session:active:sid-2"), eq("0"), any(Duration.class));
    }

    @Test
    void revokedSessionIsInactive() {
        when(redis.hasKey("atry:session:active:sid-3")).thenReturn(false);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(0L), eq("sid-3"))).thenReturn(0);

        assertThat(service.isActive(0L, "sid-3")).isFalse();
    }

    @Test
    void revokeDeletesCacheOnlyWhenUpdated() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        assertThat(service.revoke(0L, 7L, "sid-4", "USER_REVOKED")).isTrue();
        verify(redis).delete("atry:session:active:sid-4");
    }

    @Test
    void legacyTokenWithoutSidIsAllowed() {
        assertThat(service.isActive(0L, null)).isTrue();
        assertThat(service.isActive(0L, "  ")).isTrue();
        verify(jdbc, org.mockito.Mockito.never()).queryForObject(anyString(), eq(Integer.class),
                any(), any());
    }
}
