package com.wherelee.base.modules.payment;

/** 支付单状态词表（与 t_payment_order.status 一致）。 */
public final class PaymentStatus {

    public static final String CREATED = "CREATED";
    public static final String PAYING = "PAYING";
    public static final String PAID = "PAID";
    public static final String FAILED = "FAILED";
    public static final String CLOSED = "CLOSED";
    public static final String EXPIRED = "EXPIRED";
    public static final String REFUNDING = "REFUNDING";
    public static final String REFUNDED = "REFUNDED";
    public static final String REFUND_FAILED = "REFUND_FAILED";

    private PaymentStatus() {
    }
}
