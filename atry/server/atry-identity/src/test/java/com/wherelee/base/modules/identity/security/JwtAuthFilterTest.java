package com.wherelee.base.modules.identity.security;

import com.wherelee.base.modules.identity.user.User;
import com.wherelee.base.modules.identity.user.UserRepository;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JWT 鉴权过滤器测试。
 *
 * <p>业务不变式：公开路径/OPTIONS 放行；受保护接口无 token/坏 token/被禁用账号/版本失效一律 401；
 * 首登未改密返回 428 且只放行改密与 /me；鉴权成功把租户写入上下文并在请求结束清理。</p>
 */
class JwtAuthFilterTest {

    private JwtService jwtService;
    private UserRepository userRepository;
    private com.wherelee.base.modules.identity.session.SessionService sessionService;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        userRepository = mock(UserRepository.class);
        sessionService = mock(com.wherelee.base.modules.identity.session.SessionService.class);
        filter = new JwtAuthFilter(jwtService, userRepository, sessionService);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    private static User user(boolean active, int tokenVersion, boolean mustChange, long tenantId) {
        return new User(1L, tenantId, "alice", "hash", "爱丽丝", "USER",
                active ? "ACTIVE" : "DISABLED", mustChange, tokenVersion);
    }

    private MockHttpServletResponse run(String method, String path, String authorization,
                                        FilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void 公开路径与OPTIONS请求直接放行() throws Exception {
        AtomicReference<Boolean> passed = new AtomicReference<>(false);
        FilterChain chain = (req, res) -> passed.set(true);

        run("GET", "/api/health", null, chain);
        run("POST", "/api/auth/login", null, chain);
        run("POST", "/api/auth/invitations/preview", null, chain);
        run("POST", "/api/auth/invitations/accept", null, chain);
        run("POST", "/api/payments/alipay/notify", null, chain);
        run("OPTIONS", "/api/admin/users", null, chain);
        run("GET", "/actuator/health", null, chain);

        assertThat(passed.get()).isTrue();
    }

    @Test
    void 受保护接口缺少token返回401() throws Exception {
        MockHttpServletResponse response = run("GET", "/api/admin/users", null, (req, res) -> {
        });
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("请先登录");
    }

    @Test
    void 非Bearer格式返回401() throws Exception {
        MockHttpServletResponse response =
                run("GET", "/api/admin/users", "Basic abc", (req, res) -> {
                });
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void token解析失败返回401() throws Exception {
        when(jwtService.parse(anyString())).thenThrow(new JwtException("bad"));
        MockHttpServletResponse response =
                run("GET", "/api/auth/me", "Bearer bad-token", (req, res) -> {
                });
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("登录已失效");
    }

    @Test
    void 账号不存在或已禁用返回401() throws Exception {
        when(jwtService.parse(anyString())).thenReturn(new JwtService.TokenPayload(1L, 0, 0L));
        when(userRepository.findById(1L)).thenReturn(Optional.empty());
        assertThat(run("GET", "/api/auth/me", "Bearer t", (req, res) -> {
        }).getStatus()).isEqualTo(401);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user(false, 0, false, 0L)));
        MockHttpServletResponse disabled = run("GET", "/api/auth/me", "Bearer t", (req, res) -> {
        });
        assertThat(disabled.getStatus()).isEqualTo(401);
        assertThat(disabled.getContentAsString()).contains("账号不可用");
    }

    @Test
    void token版本失效返回401() throws Exception {
        when(jwtService.parse(anyString())).thenReturn(new JwtService.TokenPayload(1L, 1, 0L));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(true, 2, false, 0L)));

        MockHttpServletResponse response = run("GET", "/api/auth/me", "Bearer t", (req, res) -> {
        });
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("登录已失效");
    }

    @Test
    void 首登未改密时除改密与me外返回428() throws Exception {
        when(jwtService.parse(anyString())).thenReturn(new JwtService.TokenPayload(1L, 0, 0L));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(true, 0, true, 0L)));

        MockHttpServletResponse blocked = run("GET", "/api/admin/users", "Bearer t", (req, res) -> {
        });
        assertThat(blocked.getStatus()).isEqualTo(428);
        assertThat(blocked.getContentAsString()).contains("修改初始密码");

        AtomicReference<Boolean> passed = new AtomicReference<>(false);
        FilterChain chain = (req, res) -> passed.set(true);
        run("GET", "/api/auth/me", "Bearer t", chain);
        run("PUT", "/api/auth/password", "Bearer t", chain);
        assertThat(passed.get()).isTrue();
    }

    @Test
    void 鉴权成功写入请求属性与租户上下文_请求结束清理() throws Exception {
        when(jwtService.parse(anyString())).thenReturn(new JwtService.TokenPayload(88L, 0, 9L));
        when(userRepository.findById(88L)).thenReturn(Optional.of(user(true, 0, false, 9L)));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader("Authorization", "Bearer good");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Object> userId = new AtomicReference<>();
        AtomicReference<Object> tenantId = new AtomicReference<>();
        AtomicReference<Long> contextTenant = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            userId.set(((MockHttpServletRequest) req).getAttribute("userId"));
            tenantId.set(((MockHttpServletRequest) req).getAttribute("tenantId"));
            contextTenant.set(TenantContext.currentOrNull());
        };
        filter.doFilter(request, response, chain);

        // 请求属性里的 userId 以数据库账号 id 为准（不是 token subject 的展示值）
        assertThat(userId.get()).isEqualTo(1L);
        assertThat(tenantId.get()).isEqualTo(9L);
        assertThat(contextTenant.get()).isEqualTo(9L);
        // 过滤器 finally 清理，避免线程池复用导致下一个请求串租户
        assertThat(TenantContext.currentOrNull()).isNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void 请求链异常后租户上下文同样被清理() throws Exception {
        when(jwtService.parse(anyString())).thenReturn(new JwtService.TokenPayload(88L, 0, 9L));
        when(userRepository.findById(88L)).thenReturn(Optional.of(user(true, 0, false, 9L)));

        FilterChain exploding = (req, res) -> {
            throw new IllegalStateException("downstream failed");
        };
        try {
            run("GET", "/api/auth/me", "Bearer good", exploding);
        } catch (IllegalStateException ignored) {
            // 下游异常原样抛出，但 finally 必须清理租户上下文
        }
        assertThat(TenantContext.currentOrNull()).isNull();
    }
}
