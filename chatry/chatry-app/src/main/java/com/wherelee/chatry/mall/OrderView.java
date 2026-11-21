package com.wherelee.chatry.mall;

import java.time.Instant;

/** 订单视图。 */
public record OrderView(long id, String orderNo, long skuId, String skuTitle,
                        long amountCents, long creditCents, String status, String paymentNo,
                        boolean flashSale, Instant expireAt, Instant paidAt, Instant createdAt) {

    public static OrderView from(MallOrder order) {
        return new OrderView(order.id(), order.orderNo(), order.skuId(), order.skuTitle(),
                order.amountCents(), order.creditCents(), order.status(), order.paymentNo(),
                order.flashSale(), order.expireAt(), order.paidAt(), order.createdAt());
    }
}
