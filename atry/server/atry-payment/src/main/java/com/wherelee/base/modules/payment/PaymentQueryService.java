package com.wherelee.base.modules.payment;

import com.wherelee.base.modules.payment.api.PaymentEventTypes;
import com.wherelee.base.modules.payment.api.PaymentSettledEvent;
import com.wherelee.base.modules.payment.outbox.PaymentOutboxRepository;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import com.wherelee.base.modules.platform.tracing.TraceSpans;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 主动查单（支付域）：扫描 PAYING 支付单向渠道对账，而不是扫描订单。
 *
 * <p>结果统一转成领域事件：支付成功 → PAYMENT_SUCCEEDED（复用确认逻辑保证幂等）；
 * 渠道确认无单且支付单已过期 → 先尝试渠道关单，再本地关单并发 PAYMENT_EXPIRED，业务侧自行关单。</p>
 *
 * <p>主动查单是"无公网回调域名"场景下的结算兜底：没有配置 notify_url 时，靠
 * {@link PaymentQueryJob} 定时轮询渠道状态完成确认。</p>
 */
@Slf4j
@Service
public class PaymentQueryService {

    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentConfirmationService confirmationService;
    private final PaymentOutboxRepository outboxRepository;
    private final PaymentProperties properties;
    private final TraceSpans traceSpans;
    private final PaymentMetrics metrics;

    public PaymentQueryService(PaymentOrderRepository paymentOrderRepository,
                               PaymentRepository paymentRepository,
                               PaymentGateway paymentGateway,
                               PaymentConfirmationService confirmationService,
                               PaymentOutboxRepository outboxRepository,
                               PaymentProperties properties,
                               TraceSpans traceSpans,
                               PaymentMetrics metrics) {
        this.paymentOrderRepository = paymentOrderRepository;
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.confirmationService = confirmationService;
        this.outboxRepository = outboxRepository;
        this.properties = properties;
        this.traceSpans = traceSpans;
        this.metrics = metrics;
    }

    /** 扫描一批到期的支付中支付单，返回处理条数。 */
    public int queryPending() {
        List<PaymentOrderView> payments = paymentOrderRepository.findPayingOlderThan(
                properties.queryAfterSeconds(), properties.reconcileBatchSize());
        int handled = 0;
        for (PaymentOrderView payment : payments) {
            try {
                if (query(payment)) {
                    handled++;
                }
            } catch (Exception e) {
                log.warn("支付单主动查单失败 paymentNo={}", payment.paymentNo(), e);
            }
        }
        return handled;
    }

    /** 对单个支付单发起查单；返回是否产生了状态推进。 */
    public boolean query(PaymentOrderView payment) {
        PaymentAttemptRecord attempt = paymentRepository
                .findLatestAttemptByPaymentNo(payment.paymentNo()).orElse(null);
        PaymentGateway.PaymentQueryResult result = traceSpans.trace("payment.gateway.query", TraceSpans.tags(
                "payment.no", payment.paymentNo(),
                "payment.channel", paymentGateway.channel()
        ), () -> paymentGateway.query(payment, attempt));

        if ("PAID".equals(result.status())) {
            String tradeNo = result.tradeNo() != null ? result.tradeNo() : payment.providerTradeNo();
            PaymentConfirmationService.ConfirmationResult confirmed = confirmationService.accept(
                    new PaymentNotification(
                            paymentGateway.channel(),
                            "QUERY:" + payment.paymentNo() + ":" + tradeNo,
                            payment.bizNo(),
                            "PAYMENT_SUCCESS",
                            result.amountCents(),
                            tradeNo,
                            null), payment.paymentNo());
            if (!confirmed.success()) {
                // 渠道说已支付但确认被拒（典型：金额不一致）：不能算结算成功，
                // 记录指标与告警，保留支付单等人工处理，别每轮刷一行「已结算」。
                metrics.queryRejected();
                log.warn("payment_query_rejected paymentNo={} tradeNo={} amountCents={} reason={}",
                        payment.paymentNo(), tradeNo, result.amountCents(), confirmed.message());
                return true;
            }
            log.info("payment_query_settled paymentNo={} tradeNo={} amountCents={}",
                    payment.paymentNo(), tradeNo, result.amountCents());
            return true;
        }

        boolean notTraded = "FAILED".equals(result.status())
                || "NOT_FOUND".equals(result.status())
                || "CREATED".equals(result.status());
        if (notTraded && expired(payment)) {
            // FAILED 表示渠道侧交易已关闭；其余情况先尝试渠道关单，避免本地关单后用户仍能付款。
            if (!"FAILED".equals(result.status())) {
                PaymentGateway.CloseResult close = traceSpans.trace("payment.gateway.close", TraceSpans.tags(
                        "payment.no", payment.paymentNo(),
                        "payment.channel", paymentGateway.channel()
                ), () -> paymentGateway.close(payment, attempt));
                if (close == null) {
                    close = PaymentGateway.CloseResult.notFound("渠道未返回关单结果，按本地关单处理");
                }
                if (!close.closed() && !close.notFound()) {
                    log.warn("渠道关单未完成，保留支付单等待下次查单 paymentNo={} message={}",
                            payment.paymentNo(), close.message());
                    return false;
                }
            }
            // 关单与过期事件必须归属支付单自己的租户：定时查单线程处于系统作用域（无租户上下文），
            // 自动注入被跳过，不显式回填的话发件箱会落默认租户 0。
            Long previousTenant = TenantContext.currentOrNull();
            TenantContext.set(payment.tenantId());
            boolean closed;
            try {
                closed = paymentOrderRepository.markClosed(payment.paymentNo()) > 0;
                if (closed) {
                    outboxRepository.append(new PaymentSettledEvent(
                            PaymentEventTypes.PAYMENT_EXPIRED,
                            payment.paymentNo(), payment.bizType(), payment.bizNo(),
                            payment.amountCents(), payment.currency(), paymentGateway.channel(),
                            null, LocalDateTime.now()));
                }
            } finally {
                TenantContext.set(previousTenant);
            }
            if (closed) {
                log.info("payment_query_expired paymentNo={} bizNo={}", payment.paymentNo(), payment.bizNo());
                return true;
            }
        }
        return false;
    }

    /** 按支付单号查单（运营手动补偿/管理员立即查单用）。 */
    public boolean queryByPaymentNo(String paymentNo) {
        return paymentOrderRepository.findByPaymentNo(paymentNo).map(this::query).orElse(false);
    }

    /** 按业务单号查单（运营手动补偿/管理员立即查单用）。 */
    public boolean queryByBizNo(String bizNo) {
        return paymentOrderRepository.findByBizNo(bizNo).map(this::query).orElse(false);
    }

    private boolean expired(PaymentOrderView payment) {
        return payment.expireAt() != null && payment.expireAt().isBefore(LocalDateTime.now());
    }
}
