package com.wherelee.base.modules.payment;

import com.wherelee.base.modules.payment.api.PaymentEventTypes;
import com.wherelee.base.modules.payment.api.PaymentSettledEvent;
import com.wherelee.base.modules.payment.outbox.PaymentOutboxRepository;
import com.wherelee.base.modules.platform.alert.AlertPublisher;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import com.wherelee.base.modules.platform.tracing.TraceSpans;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 支付结果确认（纯支付域）：只改支付单、只写发件箱，不碰业务单据/履约。
 *
 * <p>业务状态由订单侧消费 {@link PaymentSettledEvent} 完成——这就是"支付反转"的核心：
 * 依赖方向从 payment → order 变成 order → payment.api。</p>
 */
@Slf4j
@Service
public class PaymentConfirmationService {

    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentOutboxRepository outboxRepository;
    private final AlertPublisher alertPublisher;
    private final PaymentMetrics metrics;
    private final TraceSpans traceSpans;

    public PaymentConfirmationService(PaymentOrderRepository paymentOrderRepository,
                                      PaymentRepository paymentRepository,
                                      PaymentOutboxRepository outboxRepository,
                                      AlertPublisher alertPublisher,
                                      PaymentMetrics metrics,
                                      TraceSpans traceSpans) {
        this.paymentOrderRepository = paymentOrderRepository;
        this.paymentRepository = paymentRepository;
        this.outboxRepository = outboxRepository;
        this.alertPublisher = alertPublisher;
        this.metrics = metrics;
        this.traceSpans = traceSpans;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ConfirmationResult accept(PaymentNotification notification) {
        return accept(notification, null);
    }

    /**
     * 确认支付结果。
     *
     * @param paymentNo 已由调用方（渠道回调/查单）精确定位到的支付单号；为空时回退按业务单号查找
     *                  （兼容历史调用）。渠道交易号与支付单的绑定关系由渠道适配器按 (channel, provider_trade_no)
     *                  精确解析，避免不同业务方 bizNo 冲突时确认到错误支付单。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ConfirmationResult accept(PaymentNotification notification, String paymentNo) {
        return traceSpans.trace("payment.confirm", TraceSpans.tags(
                "payment.bizNo", notification.orderNo(),
                "payment.provider", notification.provider(),
                "payment.event.type", notification.eventType()
        ), () -> acceptInternal(notification, paymentNo));
    }

    private ConfirmationResult acceptInternal(PaymentNotification notification, String paymentNo) {
        PaymentOrderView payment = (paymentNo != null && !paymentNo.isBlank()
                ? paymentOrderRepository.findByPaymentNo(paymentNo)
                : paymentOrderRepository.findByBizNo(notification.orderNo()))
                .orElse(null);
        if (payment == null) {
            alert("PAYMENT_UNKNOWN_ORDER", "支付通知包含未知支付单 bizNo=" + notification.orderNo()
                    + (paymentNo == null ? "" : " paymentNo=" + paymentNo));
            return ConfirmationResult.rejected("未知支付单");
        }
        // 回调/查单线程在系统作用域内跨租户定位到支付单，后续更新与发件箱写入必须
        // 归属支付单自己的租户；try/finally 恢复进入前的上下文（请求线程与后台线程都适用）。
        Long previousTenant = TenantContext.currentOrNull();
        TenantContext.set(payment.tenantId());
        try {
            return confirm(notification, payment);
        } finally {
            TenantContext.set(previousTenant);
        }
    }

    /** 在支付单归属租户的上下文中执行确认（只依赖 payment 快照，不再跨租户查询）。 */
    private ConfirmationResult confirm(PaymentNotification notification, PaymentOrderView payment) {
        boolean successEvent = "PAYMENT_SUCCESS".equals(notification.eventType());
        if (successEvent && notification.amountCents() != payment.amountCents()) {
            metrics.amountMismatch();
            paymentRepository.insertEvent(notification.provider(), notification.eventKey(),
                    payment.paymentNo(), notification.orderNo(), notification.eventType(),
                    notification.amountCents(), null);
            paymentRepository.findEvent(notification.provider(), notification.eventKey())
                    .ifPresent(event -> paymentRepository.markEventFailed(event.id(),
                            "金额不一致 expected=" + payment.amountCents()
                                    + " actual=" + notification.amountCents()));
            alert("PAYMENT_AMOUNT_MISMATCH", "支付金额不一致 paymentNo=" + payment.paymentNo()
                    + " expected=" + payment.amountCents() + " actual=" + notification.amountCents());
            return ConfirmationResult.rejected("支付金额不一致");
        }

        boolean inserted = paymentRepository.insertEvent(notification.provider(), notification.eventKey(),
                payment.paymentNo(), notification.orderNo(), notification.eventType(),
                notification.amountCents(), null);
        if (!inserted) {
            metrics.callbackDuplicate();
            PaymentEventRecord event = paymentRepository
                    .findEvent(notification.provider(), notification.eventKey()).orElse(null);
            boolean processed = event != null && "PROCESSED".equals(event.processStatus());
            return new ConfirmationResult(true, true, "重复支付事件已忽略",
                    payment.paymentNo(), payment.bizNo(), payment.status(),
                    payment.amountCents(), payment.channel(), payment.providerTradeNo());
        }
        PaymentEventRecord event = paymentRepository
                .findEvent(notification.provider(), notification.eventKey()).orElse(null);

        PaymentAttemptRecord attempt = paymentRepository.findLatestAttemptByPaymentNo(payment.paymentNo())
                .orElse(null);

        if (successEvent) {
            // 状态 CAS：只有 CREATED/PAYING 能进入 PAID。影响行数为 0 时先看当前状态，
            // 已支付按重复事件处理；CLOSED/EXPIRED/FAILED 一律拒绝，绝不能继续写发件箱。
            int updated = paymentOrderRepository.markPaid(payment.paymentNo(),
                    notification.provider(), notification.tradeNo());
            if (updated == 0) {
                PaymentOrderView current = paymentOrderRepository.findByPaymentNo(payment.paymentNo())
                        .orElse(payment);
                if ("PAID".equals(current.status())) {
                    if (attempt != null) {
                        paymentRepository.markAttemptSuccess(attempt.id());
                    }
                    if (event != null) {
                        paymentRepository.markEventProcessed(event.id());
                    }
                    metrics.callbackDuplicate();
                    return result(true, true, "支付单已支付，重复成功事件已忽略", current);
                }
                rejectEvent(event, "支付单状态 " + current.status() + " 不允许确认支付");
                alert("PAYMENT_STATE_CONFLICT", "支付单状态冲突 paymentNo=" + payment.paymentNo()
                        + " status=" + current.status() + " eventKey=" + notification.eventKey());
                return result(false, false, "支付单状态 " + current.status() + " 不允许确认支付", current);
            }
            if (attempt != null) {
                paymentRepository.markAttemptSuccess(attempt.id());
            }
            appendEvent(PaymentEventTypes.PAYMENT_SUCCEEDED, payment, notification);
            metrics.paymentConfirmed();
        } else {
            int updated = paymentOrderRepository.markFailed(payment.paymentNo());
            if (updated == 0) {
                PaymentOrderView current = paymentOrderRepository.findByPaymentNo(payment.paymentNo())
                        .orElse(payment);
                if ("FAILED".equals(current.status())) {
                    if (attempt != null) {
                        paymentRepository.markAttemptFailed(attempt.id(), "PROVIDER_FAILED", "支付平台返回失败");
                    }
                    if (event != null) {
                        paymentRepository.markEventProcessed(event.id());
                    }
                    metrics.callbackDuplicate();
                    return result(true, true, "支付单已失败，重复失败事件已忽略", current);
                }
                rejectEvent(event, "支付单状态 " + current.status() + " 不允许标记失败");
                alert("PAYMENT_STATE_CONFLICT", "支付单状态冲突 paymentNo=" + payment.paymentNo()
                        + " status=" + current.status() + " eventKey=" + notification.eventKey());
                return result(false, false, "支付单状态 " + current.status() + " 不允许标记失败", current);
            }
            if (attempt != null) {
                paymentRepository.markAttemptFailed(attempt.id(), "PROVIDER_FAILED", "支付平台返回失败");
            }
            appendEvent(PaymentEventTypes.PAYMENT_FAILED, payment, notification);
            metrics.paymentFailed();
        }
        if (event != null) {
            paymentRepository.markEventProcessed(event.id());
        }
        PaymentOrderView updated = paymentOrderRepository.findByPaymentNo(payment.paymentNo()).orElse(payment);
        return result(true, false, successEvent ? "支付已确认" : "支付失败已入账", updated);
    }

    private void rejectEvent(PaymentEventRecord event, String reason) {
        if (event != null) {
            paymentRepository.markEventFailed(event.id(), reason);
        }
    }

    private ConfirmationResult result(boolean success, boolean duplicate, String message,
                                      PaymentOrderView payment) {
        return new ConfirmationResult(success, duplicate, message, payment.paymentNo(), payment.bizNo(),
                payment.status(), payment.amountCents(), payment.channel(), payment.providerTradeNo());
    }

    private void appendEvent(String type, PaymentOrderView payment, PaymentNotification notification) {
        outboxRepository.append(new PaymentSettledEvent(
                type, payment.paymentNo(), payment.bizType(), payment.bizNo(),
                payment.amountCents(), payment.currency(), notification.provider(),
                notification.tradeNo(), LocalDateTime.now()));
    }

    private void alert(String type, String message) {
        try {
            alertPublisher.publish(type, "ERROR", message, false, "webhook delivery not configured for payment alerts");
        } catch (Exception e) {
            log.warn("支付告警入库失败 type={} message={}", type, message, e);
        }
    }

    /** 确认结果（支付域视角）。 */
    public record ConfirmationResult(boolean success, boolean duplicate, String message,
                                     String paymentNo, String bizNo, String status,
                                     long amountCents, String channel, String providerTradeNo) {

        static ConfirmationResult rejected(String message) {
            return new ConfirmationResult(false, false, message, null, null, "UNKNOWN", 0, null, null);
        }
    }
}
