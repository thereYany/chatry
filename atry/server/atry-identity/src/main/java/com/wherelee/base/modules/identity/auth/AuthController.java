package com.wherelee.base.modules.identity.auth;

import com.wherelee.base.modules.identity.auth.dto.ChangePasswordRequest;
import com.wherelee.base.modules.identity.auth.dto.LoginRequest;
import com.wherelee.base.modules.identity.auth.dto.LoginResponse;
import com.wherelee.base.modules.identity.auth.dto.RegisterRequest;
import com.wherelee.base.modules.identity.auth.dto.UserInfo;
import com.wherelee.base.modules.identity.security.ClientIpResolver;
import com.wherelee.base.modules.identity.security.LoginThrottleService;
import com.wherelee.base.modules.identity.session.SessionService;
import com.wherelee.base.modules.identity.session.SessionView;
import com.wherelee.base.modules.platform.aop.Measured;
import com.wherelee.base.modules.platform.aop.RateLimit;
import com.wherelee.base.modules.platform.common.BizException;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final LoginThrottleService loginThrottleService;
    private final AuthAuditService authAuditService;
    private final ClientIpResolver clientIpResolver;
    private final SessionService sessionService;

    public AuthController(AuthService authService,
                          LoginThrottleService loginThrottleService,
                          AuthAuditService authAuditService,
                          ClientIpResolver clientIpResolver,
                          SessionService sessionService) {
        this.authService = authService;
        this.loginThrottleService = loginThrottleService;
        this.authAuditService = authAuditService;
        this.clientIpResolver = clientIpResolver;
        this.sessionService = sessionService;
    }

    @PostMapping("/login")
    @Measured("auth.login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String clientIp = clientIpResolver.resolve(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        LoginResponse response;
        try {
            loginThrottleService.check(request.username(), clientIp);
            response = authService.login(request, clientIp, userAgent);
        } catch (RuntimeException e) {
            loginThrottleService.recordFailure(request.username(), clientIp);
            authAuditService.loginFailure(request.username(), clientIp, userAgent, failureReason(e));
            throw e;
        }
        loginThrottleService.clear(request.username(), clientIp);
        authAuditService.loginSuccess(response.user().id(), response.user().username(), clientIp, userAgent);
        return response;
    }

    /**
     * 自助注册：用户名 + 密码 + 昵称 + 邮箱（可选）。
     *
     * <p>无验证码/无邮件通道，靠 IP 维度限流（每 IP 每小时 10 次）+ 唯一键 + 注册开关防刷。</p>
     */
    @PostMapping("/register")
    @RateLimit(key = "auth:register", limit = 10, windowSeconds = 3600, dimension = RateLimit.Dimension.IP)
    public LoginResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        String clientIp = clientIpResolver.resolve(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        LoginResponse response = authService.register(request, clientIp, userAgent);
        authAuditService.register(response.user().id(), response.user().username(), clientIp, userAgent);
        return response;
    }

    @GetMapping("/me")
    public UserInfo me(@RequestAttribute("userId") Long userId) {
        return authService.me(userId);
    }

    /** 会话列表：用于账号安全页展示并踢掉陌生设备。 */
    @GetMapping("/sessions")
    public java.util.List<SessionView> sessions(@RequestAttribute("tenantId") Long tenantId,
                                                @RequestAttribute("userId") Long userId,
                                                @RequestAttribute(value = "sessionId", required = false)
                                                String sessionId) {
        return sessionService.list(tenantId, userId, sessionId);
    }

    /** 吊销指定会话（只能吊销自己的）；踢掉当前会话等价于登出。 */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> revokeSession(@RequestAttribute("tenantId") Long tenantId,
                                              @RequestAttribute("userId") Long userId,
                                              @PathVariable String sessionId) {
        if (!sessionService.revoke(tenantId, userId, sessionId, "USER_REVOKED")) {
            throw BizException.notFound("会话不存在或已失效");
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestAttribute("tenantId") Long tenantId,
                                       @RequestAttribute("userId") Long userId,
                                       @RequestAttribute("username") String username,
                                       @RequestAttribute(value = "sessionId", required = false) String sessionId,
                                       HttpServletRequest httpRequest) {
        if (sessionId != null && !sessionId.isBlank()) {
            sessionService.revoke(tenantId, userId, sessionId, "LOGOUT");
        }
        authAuditService.logout(userId, username, clientIpResolver.resolve(httpRequest),
                httpRequest.getHeader("User-Agent"));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/password")
    public void changePassword(@RequestAttribute("userId") Long userId,
                               @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(userId, request);
    }

    private String failureReason(RuntimeException e) {
        if (e instanceof BizException biz) {
            return switch (biz.status().value()) {
                case 429 -> "THROTTLED";
                case 401 -> "BAD_CREDENTIALS";
                default -> "BIZ_" + biz.status().value();
            };
        }
        return "ERROR";
    }
}
