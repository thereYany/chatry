package com.wherelee.base.modules.identity.auth;

import com.wherelee.base.modules.platform.aop.RateLimitExceededEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 注册接口被限流时补一条认证审计（平台切面只发事件，不依赖身份域）。 */
@Component
public class RegistrationThrottleAuditListener {

    private final AuthAuditService authAuditService;

    public RegistrationThrottleAuditListener(AuthAuditService authAuditService) {
        this.authAuditService = authAuditService;
    }

    @EventListener
    public void onRateLimitExceeded(RateLimitExceededEvent event) {
        if ("auth:register".equals(event.key())) {
            authAuditService.registerThrottled(event.subject());
        }
    }
}
