package com.wherelee.base.modules.identity.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 认证审计：审计失败不能影响认证主流程（既不阻断登录，也不静默丢失告警）。
 *
 * <p>写入失败只记 WARN + 继续；后续可用指标/告警发现审计链路异常。</p>
 */
@Service
public class AuthAuditService {

    private static final Logger log = LoggerFactory.getLogger(AuthAuditService.class);

    private final AuthEventRepository repository;

    public AuthAuditService(AuthEventRepository repository) {
        this.repository = repository;
    }

    public void loginSuccess(Long userId, String username, String ip, String userAgent) {
        record(userId, username, AuthEventTypes.LOGIN_SUCCESS, true, ip, userAgent, null);
    }

    public void loginFailure(String username, String ip, String userAgent, String reason) {
        record(null, username, AuthEventTypes.LOGIN_FAILURE, false, ip, userAgent, reason);
    }

    public void register(Long userId, String username, String ip, String userAgent) {
        record(userId, username, AuthEventTypes.REGISTER, true, ip, userAgent, null);
    }

    /** 注册被限流：没有账号信息，只记录来源 IP 与事件类型。 */
    public void registerThrottled(String ip) {
        record(null, null, AuthEventTypes.REGISTER_THROTTLED, false, ip, null, "RATE_LIMITED");
    }

    public void logout(Long userId, String username, String ip, String userAgent) {
        record(userId, username, AuthEventTypes.LOGOUT, true, ip, userAgent, null);
    }

    private void record(Long userId, String username, String eventType, boolean success,
                        String ip, String userAgent, String detail) {
        try {
            repository.insert(userId, username, eventType, success, ip, userAgent, detail);
        } catch (RuntimeException e) {
            log.warn("认证审计写入失败 type={} username={} cause={}", eventType, username,
                    e.getClass().getSimpleName());
        }
    }
}
