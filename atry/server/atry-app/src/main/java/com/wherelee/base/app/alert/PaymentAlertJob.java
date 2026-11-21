package com.wherelee.base.app.alert;

import com.wherelee.base.modules.contracts.payment.PaymentAlertQueries;
import com.wherelee.base.modules.platform.alert.AlertPublisher;
import com.wherelee.base.modules.platform.resilience.LocalFallbackCache;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 支付/退款异常巡检。与媒体访问告警分离，避免把交易告警混入媒体访问日志统计。
 */
@Slf4j
@Component
public class PaymentAlertJob {

    private final AlertProperties properties;
    private final PaymentAlertQueries paymentAlertQueries;
    private final AlertNotifier notifier;
    private final AlertPublisher alertPublisher;
    private final LocalFallbackCache dedupCache;

    public PaymentAlertJob(AlertProperties properties,
                           PaymentAlertQueries paymentAlertQueries,
                           AlertNotifier notifier,
                           AlertPublisher alertPublisher,
                           LocalFallbackCache dedupCache) {
        this.properties = properties;
        this.paymentAlertQueries = paymentAlertQueries;
        this.notifier = notifier;
        this.alertPublisher = alertPublisher;
        this.dedupCache = dedupCache;
    }

    @Scheduled(
            fixedDelayString = "${app.alert.evaluate-interval-ms:300000}",
            initialDelayString = "${app.alert.initial-delay-ms:60000}"
    )
    public void evaluate() {
        if (!properties.enabled()) {
            return;
        }
        // 巡检是平台级任务：必须跨租户统计，不能按调度线程的默认租户 0 过滤。
        TenantContext.runAsSystem(this::evaluateInternal);
    }

    private void evaluateInternal() {
        LocalDateTime now = LocalDateTime.now();
        long paidStuck = paymentAlertQueries.countSettlementUndeliveredBefore(
                now.minusMinutes(properties.safePaymentPaidUnfulfilledMinutes()));
        if (paidStuck > 0) {
            emit("PAYMENT_SETTLEMENT_UNDELIVERED", "HIGH",
                    "支付成功但结算事件未投递成功 " + paidStuck + " 笔");
        }
        long payingStuck = paymentAlertQueries.countPayingPaymentBefore(
                now.minusMinutes(properties.safePaymentPayingStuckMinutes()));
        if (payingStuck > 0) {
            emit("PAYMENT_PAYING_STUCK", "MEDIUM",
                    "支付中超过 " + properties.safePaymentPayingStuckMinutes() + " 分钟支付单 " + payingStuck + " 笔");
        }
        long refundExhausted = paymentAlertQueries.countRefundRetryExhausted();
        if (refundExhausted > 0 && refundExhausted >= properties.safePaymentRefundRetryExhaustedThreshold()) {
            emit("PAYMENT_REFUND_RETRY_EXHAUSTED", "HIGH",
                    "退款重试耗尽订单 " + refundExhausted + " 笔");
        }
    }

    private void emit(String type, String severity, String message) {
        Duration ttl = Duration.ofMinutes(Math.max(1, properties.dedupMinutes()));
        if (!dedupCache.reserve("payment-alert:" + type, ttl)) {
            return;
        }
        AlertNotifier.DeliveryResult result = notifier.send(message);
        alertPublisher.publish(type, severity, message, result.delivered(), result.error());
        log.warn("支付告警 type={} severity={} delivered={} message={}",
                type, severity, result.delivered(), message);
    }
}
