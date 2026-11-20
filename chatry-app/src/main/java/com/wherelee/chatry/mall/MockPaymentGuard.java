package com.wherelee.chatry.mall;

import com.wherelee.base.modules.payment.PaymentProperties;
import com.wherelee.base.modules.platform.common.BizException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;

/**
 * Mock 支付入口守卫：只允许在「非生产 + 渠道 mock + mockEnabled」时使用。
 *
 * <p>真正的资金渠道接入后，模拟支付端点必须表现为不存在，不能只靠前端隐藏按钮。</p>
 */
@Component
public class MockPaymentGuard {

    private final PaymentProperties properties;
    private final Environment environment;

    public MockPaymentGuard(PaymentProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    public void requireEnabled() {
        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        boolean mockProvider = "mock".equalsIgnoreCase(
                properties.provider() == null ? "" : properties.provider().trim().toLowerCase(Locale.ROOT));
        if (prod || !mockProvider || !properties.mockEnabled()) {
            throw BizException.notFound("模拟支付未启用");
        }
    }
}
