package com.wherelee.chatry.mall;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 商城配置。
 *
 * @param orderExpireMinutes 未支付订单关单并回补库存的时限（分钟）
 * @param expiryPollMs       关单扫描间隔
 */
@ConfigurationProperties(prefix = "chatry.mall")
public record MallProperties(Integer orderExpireMinutes, Long expiryPollMs) {

    public MallProperties {
        orderExpireMinutes = orderExpireMinutes == null || orderExpireMinutes <= 0 ? 15 : orderExpireMinutes;
        expiryPollMs = expiryPollMs == null || expiryPollMs <= 0 ? 30_000L : expiryPollMs;
    }
}
