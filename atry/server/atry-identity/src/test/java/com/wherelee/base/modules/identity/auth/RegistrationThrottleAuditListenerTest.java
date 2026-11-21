package com.wherelee.base.modules.identity.auth;

import com.wherelee.base.modules.platform.aop.RateLimitExceededEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RegistrationThrottleAuditListenerTest {

    private final AuthAuditService audit = mock(AuthAuditService.class);
    private final RegistrationThrottleAuditListener listener = new RegistrationThrottleAuditListener(audit);

    @Test
    void recordsRegistrationThrottledEvent() {
        listener.onRateLimitExceeded(new RateLimitExceededEvent("auth:register", "10.0.0.1"));
        verify(audit).registerThrottled("10.0.0.1");
    }

    @Test
    void ignoresOtherRateLimitedEndpoints() {
        listener.onRateLimitExceeded(new RateLimitExceededEvent("admin:createUser", "7"));
        verify(audit, never()).registerThrottled(org.mockito.ArgumentMatchers.anyString());
    }
}
