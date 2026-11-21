package com.wherelee.base.modules.payment.recon;

import java.time.LocalDateTime;

/**
 * 渠道账单的一行（渠道视角的事实）。
 *
 * <p>真实渠道（支付宝/微信）由账单文件解析得到；Mock 渠道由支付尝试表模拟。</p>
 */
public record ChannelBillLine(
        String providerTradeNo,
        long amountCents,
        String status,
        LocalDateTime paidAt
) {
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_FAILED = "FAILED";
}
