package com.wherelee.chatry.mall;

import com.wherelee.base.modules.payment.PaymentProperties;
import com.wherelee.base.modules.platform.common.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockPaymentGuardTest {

    @Test
    void mockProviderInDevIsAllowed() {
        MockPaymentGuard guard = guard("mock", true, new MockEnvironment());
        assertThatCode(guard::requireEnabled).doesNotThrowAnyException();
    }

    @Test
    void realProviderRejectsMockPay() {
        MockPaymentGuard guard = guard("alipay", false, new MockEnvironment());
        assertThatThrownBy(guard::requireEnabled)
                .isInstanceOf(BizException.class)
                .hasMessageContaining("模拟支付未启用");
    }

    @Test
    void disabledMockRejectsMockPay() {
        MockPaymentGuard guard = guard("mock", false, new MockEnvironment());
        assertThatThrownBy(guard::requireEnabled).isInstanceOf(BizException.class);
    }

    @Test
    void prodProfileRejectsMockPayEvenIfMockConfigured() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        MockPaymentGuard guard = guard("mock", true, environment);
        assertThatThrownBy(guard::requireEnabled).isInstanceOf(BizException.class);
    }

    private MockPaymentGuard guard(String provider, boolean mockEnabled, MockEnvironment environment) {
        PaymentProperties properties = new PaymentProperties(provider, mockEnabled, "mock-secret",
                15, 15, 20, 30_000L, 10_000L, 5, 10);
        return new MockPaymentGuard(properties, environment);
    }
}
