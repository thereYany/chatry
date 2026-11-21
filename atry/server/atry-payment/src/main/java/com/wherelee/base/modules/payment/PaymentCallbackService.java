package com.wherelee.base.modules.payment;

import com.wherelee.base.modules.platform.tracing.TraceSpans;
import org.springframework.stereotype.Service;

/**
 * 支付回调统一入口（纯支付域）。
 *
 * <p>渠道适配器验签后调用这里；它只做支付确认 + 写发件箱，
 * 业务单据/履约状态由业务侧消费 PaymentSettledEvent 完成。</p>
 */
@Service
public class PaymentCallbackService {

    private final PaymentConfirmationService confirmationService;
    private final TraceSpans traceSpans;

    public PaymentCallbackService(PaymentConfirmationService confirmationService,
                                  TraceSpans traceSpans) {
        this.confirmationService = confirmationService;
        this.traceSpans = traceSpans;
    }

    public PaymentConfirmationService.ConfirmationResult acceptVerified(PaymentNotification notification) {
        return acceptVerified(notification, null);
    }

    /** 渠道已精确解析出支付单号时使用，避免按业务单号回退查找产生歧义。 */
    public PaymentConfirmationService.ConfirmationResult acceptVerified(PaymentNotification notification,
                                                                        String paymentNo) {
        return traceSpans.trace("payment.confirm", TraceSpans.tags(
                "payment.bizNo", notification.orderNo(),
                "payment.provider", notification.provider(),
                "payment.event.type", notification.eventType()
        ), () -> confirmationService.accept(notification, paymentNo));
    }
}
