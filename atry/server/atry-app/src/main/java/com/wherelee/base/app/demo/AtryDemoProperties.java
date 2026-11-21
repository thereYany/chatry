package com.wherelee.base.app.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * 演示宿主配置：只服务 atry-app 的「一条 curl 看完整链路」演示，不属于内核模块。
 *
 * <p>{@code atry.demo.enabled=false} 可整体关闭演示端点（生产宿主可按需关闭）。</p>
 */
@ConfigurationProperties(prefix = "atry.demo")
public record AtryDemoProperties(
        boolean enabled,
        String currency,
        BigDecimal initialBalance,
        String defaultModel,
        int defaultMaxTokens
) {

    public AtryDemoProperties {
        currency = currency == null || currency.isBlank() ? "CNY" : currency;
        initialBalance = initialBalance == null ? new BigDecimal("10.000000") : initialBalance;
        defaultModel = defaultModel == null || defaultModel.isBlank() ? "mock-small" : defaultModel;
        defaultMaxTokens = defaultMaxTokens <= 0 ? 128 : defaultMaxTokens;
    }
}
