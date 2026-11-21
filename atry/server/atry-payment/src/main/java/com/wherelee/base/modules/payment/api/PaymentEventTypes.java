package com.wherelee.base.modules.payment.api;

/** 支付域对外发布的领域事件类型。 */
public final class PaymentEventTypes {

    public static final String PAYMENT_SUCCEEDED = "PAYMENT_SUCCEEDED";
    public static final String PAYMENT_FAILED = "PAYMENT_FAILED";
    public static final String PAYMENT_EXPIRED = "PAYMENT_EXPIRED";
    public static final String REFUND_SUCCEEDED = "REFUND_SUCCEEDED";

    private PaymentEventTypes() {
    }
}
