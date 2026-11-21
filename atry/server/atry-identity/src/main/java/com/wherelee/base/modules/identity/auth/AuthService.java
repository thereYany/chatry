package com.wherelee.base.modules.identity.auth;

import com.wherelee.base.modules.identity.auth.dto.ChangePasswordRequest;
import com.wherelee.base.modules.identity.auth.dto.LoginRequest;
import com.wherelee.base.modules.identity.auth.dto.LoginResponse;
import com.wherelee.base.modules.identity.auth.dto.RegisterRequest;
import com.wherelee.base.modules.identity.auth.dto.UserInfo;
import com.wherelee.base.modules.identity.security.JwtService;
import com.wherelee.base.modules.identity.session.SessionService;
import com.wherelee.base.modules.identity.user.User;
import com.wherelee.base.modules.identity.user.UserRepository;
import com.wherelee.base.modules.platform.common.BizException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * 认证服务：登录、会话令牌、改密，以及 0.2.x 的自助注册。
 *
 * <p>注册不接验证码/邮件通道：防刷靠 IP 限流（接口注解）、用户名/邮箱唯一键、注册开关；
 * 邮箱只做格式校验与租户内唯一，不做真实性验证。</p>
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthProperties authProperties;
    private final SessionService sessionService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       JwtService jwtService, AuthProperties authProperties,
                       SessionService sessionService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authProperties = authProperties;
        this.sessionService = sessionService;
    }

    public LoginResponse login(LoginRequest request, String clientIp, String userAgent) {
        User user = userRepository.findByUsername(request.username().trim())
                .filter(User::isActive)
                .orElse(null);
        if (user == null || !passwordEncoder.matches(request.password(), user.passwordHash())) {
            throw BizException.unauthorized("账号或密码不正确");
        }
        String sessionId = sessionService.create(user.tenantId(), user.id(), clientIp, userAgent);
        return new LoginResponse(jwtService.createToken(user, sessionId), toInfo(user),
                user.mustChangePassword());
    }

    /**
     * 自助注册：注册成功直接签发 token（免二次登录）。
     *
     * <p>用户名/邮箱撞唯一键时返回 409；注册收益（赠送额度）由垂直应用通过事件/首访幂等发放。</p>
     */
    @Transactional
    public LoginResponse register(RegisterRequest request, String clientIp, String userAgent) {
        if (!authProperties.registrationEnabled()) {
            throw new BizException(HttpStatus.FORBIDDEN, "REGISTRATION_DISABLED", "注册暂未开放");
        }
        String username = request.username().trim();
        String nickname = request.nickname().trim();
        String email = normalizeEmail(request.email());
        if (userRepository.findByUsername(username).isPresent()) {
            throw new BizException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "用户名已被占用");
        }
        if (email != null && userRepository.existsByEmail(email)) {
            throw new BizException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "邮箱已被注册");
        }
        Long userId;
        try {
            userId = userRepository.create(username, passwordEncoder.encode(request.password()),
                    nickname, authProperties.defaultRole(), false, email);
        } catch (DuplicateKeyException e) {
            // 并发注册撞唯一键：不区分用户名还是邮箱，避免侧信道探测
            throw new BizException(HttpStatus.CONFLICT, "ACCOUNT_TAKEN", "用户名或邮箱已被占用");
        }
        User user = userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new IllegalStateException("注册后账号不可读"));
        String sessionId = sessionService.create(user.tenantId(), user.id(), clientIp, userAgent);
        return new LoginResponse(jwtService.createToken(user, sessionId), toInfo(user), false);
    }

    public UserInfo me(Long userId) {
        User user = userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> BizException.unauthorized("登录已失效，请重新登录"));
        return toInfo(user);
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> BizException.unauthorized("登录已失效，请重新登录"));
        if (!passwordEncoder.matches(request.oldPassword(), user.passwordHash())) {
            throw BizException.badRequest("原密码不正确");
        }
        if (passwordEncoder.matches(request.newPassword(), user.passwordHash())) {
            throw BizException.badRequest("新密码不能与原密码相同");
        }
        userRepository.updatePassword(userId, passwordEncoder.encode(request.newPassword()));
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private UserInfo toInfo(User user) {
        return new UserInfo(user.id(), user.username(), user.nickname(), user.role());
    }
}
