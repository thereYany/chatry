package com.wherelee.base.app.alert;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 内核告警配置：webhook 投递与支付异常巡检阈值。
 *
 * <p>垂直业务的告警（如媒体访问异常）由垂直应用自己的配置与巡检任务负责，
 * 内核只保留与业务无关的支付链路巡检。</p>
 */
@ConfigurationProperties(prefix = "app.alert")
public record AlertProperties(
        boolean enabled,
        String webhookUrl,
        int dedupMinutes,
        long evaluateIntervalMs,
        long initialDelayMs,
        int paymentPaidUnfulfilledMinutes,
        int paymentPayingStuckMinutes,
        int paymentRefundRetryExhaustedThreshold
) {
    public int safePaymentPaidUnfulfilledMinutes() {
        return Math.max(1, paymentPaidUnfulfilledMinutes);
    }

    public int safePaymentPayingStuckMinutes() {
        return Math.max(1, paymentPayingStuckMinutes);
    }

    public int safePaymentRefundRetryExhaustedThreshold() {
        return Math.max(1, paymentRefundRetryExhaustedThreshold);
    }
}
