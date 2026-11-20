package com.wherelee.chatry.mall;

/** 创建订单响应（前端据此跳收银台；mock 渠道下直接展示「模拟支付」）。 */
public record CreateOrderResponse(String orderNo, String paymentNo, String cashierUrl, String status,
                                  long amountCents, long creditCents, boolean flashSale) {
}
