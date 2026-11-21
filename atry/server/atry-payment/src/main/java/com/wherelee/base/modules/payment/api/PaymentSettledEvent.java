package com.wherelee.base.modules.payment.api;

import java.time.LocalDateTime;

/**
 * 支付结果事件（对外契约）。
 *
 * <p>业务侧只依赖这个事件完成履约：支付域不再直接调用业务单据/履约实现。</p>
 */
public record PaymentSettledEvent(
        String eventType,
        String paymentNo,
        String bizType,
        String bizNo,
        long amountCents,
        String currency,
        String channel,
        String providerTradeNo,
        LocalDateTime occurredAt
) {
}
