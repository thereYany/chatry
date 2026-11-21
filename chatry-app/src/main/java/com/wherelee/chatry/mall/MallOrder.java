package com.wherelee.chatry.mall;

import java.time.Instant;

/** 商城订单。 */
public record MallOrder(
        long id,
        long tenantId,
        String orderNo,
        long userId,
        long skuId,
        String skuTitle,
        long amountCents,
        long creditCents,
        String status,
        String paymentNo,
        boolean flashSale,
        Instant expireAt,
        Instant paidAt,
        Instant createdAt,
        long refundedCents,
        String refundNo
) {
    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_PAYING = "PAYING";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    /** 支付成功但业务订单已关闭/过期：钱已收到、额度未入账，等待退款或人工处理。 */
    public static final String STATUS_PAID_AFTER_CLOSE = "PAID_AFTER_CLOSE";
    public static final String STATUS_REFUNDING = "REFUNDING";
    public static final String STATUS_REFUNDED = "REFUNDED";
    public static final String STATUS_PARTIALLY_REFUNDED = "PARTIALLY_REFUNDED";
}
