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
        Instant createdAt
) {
    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_PAYING = "PAYING";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_CLOSED = "CLOSED";
}
