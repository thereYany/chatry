package com.wherelee.base.modules.payment;

import java.time.LocalDateTime;

/**
 * 支付单视图（支付域自有类型）。
 *
 * <p>渠道适配器只认识这个类型：它没有任何「订单」语义，只有 paymentNo / bizNo /
 * 金额 / 标题 / 过期时间 / 渠道流水号。这样 payment 包不需要 import order 包。</p>
 *
 * <p>tenantId 是支付单的归属租户：回调/查单线程在系统作用域内跨租户定位到支付单后，
 * 必须用它回填 {@link com.wherelee.base.modules.platform.tenant.TenantContext}，
 * 后续状态更新与发件箱写入才归属正确租户。</p>
 */
public record PaymentOrderView(
        String paymentNo,
        String bizNo,
        String appId,
        String bizType,
        String subject,
        long amountCents,
        String currency,
        String status,
        String channel,
        String providerTradeNo,
        LocalDateTime expireAt,
        LocalDateTime paidAt,
        long tenantId
) {
}
