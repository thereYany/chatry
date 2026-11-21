package com.wherelee.base.modules.payment.api;

/**
 * 支付结果事件监听者。
 *
 * <p>实现方必须幂等：投递语义是 at-least-once，重复事件不能产生重复副作用。</p>
 */
public interface PaymentSettledListener {

    void onSettled(PaymentSettledEvent event);
}
