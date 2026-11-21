package com.wherelee.base.modules.payment;

import com.wherelee.base.modules.contracts.payment.RefundOperations;
import com.wherelee.base.modules.payment.api.PaymentEventTypes;
import com.wherelee.base.modules.payment.api.PaymentSettledEvent;
import com.wherelee.base.modules.payment.outbox.PaymentOutboxRepository;
import com.wherelee.base.modules.platform.common.BizException;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 退款编排（纯支付域）：校验可退金额 → 建退款单 → 调渠道 → 记状态 → 发 REFUND_SUCCEEDED 事件。
 *
 * <p>三条纪律：</p>
 * <ol>
 *   <li>金额不可超退：已退（成功+处理中）+ 本次 ≤ 支付单金额，靠行锁串行化；</li>
 *   <li>幂等：refundNo 唯一，重复请求返回同一退款单；渠道侧 out_request_no = refundNo；</li>
 *   <li>失败可重试：失败落 FAILED + next_retry_at，由 {@link RefundRetryJob} 退避重试，
 *       超过上限进死信并告警，不静默丢单。</li>
 * </ol>
 */
@Slf4j
@Service
public class RefundService implements RefundOperations {

    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentOutboxRepository outboxRepository;
    private final PaymentProperties properties;
    private final PaymentMetrics metrics;

    public RefundService(PaymentOrderRepository paymentOrderRepository,
                         PaymentRepository paymentRepository,
                         PaymentGateway paymentGateway,
                         PaymentOutboxRepository outboxRepository,
                         PaymentProperties properties,
                         PaymentMetrics metrics) {
        this.paymentOrderRepository = paymentOrderRepository;
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.outboxRepository = outboxRepository;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    @Transactional
    public RefundView request(RefundCommand command) {
        validate(command);
        String refundNo = command.refundNo() == null || command.refundNo().isBlank()
                ? "RF" + UUID.randomUUID().toString().replace("-", "").substring(0, 20)
                : command.refundNo().trim();
        RefundRecord existing = paymentRepository.findRefundByNo(refundNo).orElse(null);
        if (existing != null) {
            return toView(existing);
        }
        PaymentOrderView payment = paymentOrderRepository.findByPaymentNo(command.paymentNo())
                .orElseThrow(() -> BizException.notFound("支付单不存在"));
        Long previousTenant = TenantContext.currentOrNull();
        TenantContext.set(payment.tenantId());
        try {
            if (!PaymentStatus.PAID.equals(payment.status())
                    && !PaymentStatus.REFUNDING.equals(payment.status())) {
                throw new BizException(HttpStatus.CONFLICT,
                        "支付单状态 " + payment.status() + " 不允许退款");
            }
            long already = paymentRepository.sumRefundedCents(payment.paymentNo());
            if (already + command.amountCents() > payment.amountCents()) {
                throw new BizException(HttpStatus.CONFLICT, "退款金额超过可退金额");
            }
            Long refundId;
            try {
                refundId = paymentRepository.createRefund(refundNo, null, payment.paymentNo(),
                        command.amountCents(), command.reason(), command.requestedBy());
            } catch (DuplicateKeyException e) {
                return paymentRepository.findRefundByNo(refundNo).map(this::toView)
                        .orElseThrow(() -> new BizException(HttpStatus.CONFLICT, "退款单已存在"));
            }
            // 首次进入 REFUNDING（部分退款也先标 PROCESSING 的支付单）
            paymentOrderRepository.markRefunding(payment.paymentNo());
            execute(refundId, payment);
            return paymentRepository.findRefundById(refundId).map(this::toView)
                    .orElseThrow(() -> new IllegalStateException("退款单读取失败"));
        } finally {
            TenantContext.set(previousTenant);
        }
    }

    /** 执行一次渠道退款；成功写成功事件与支付单终态，失败落重试。 */
    void execute(Long refundId, PaymentOrderView payment) {
        RefundRecord refund = paymentRepository.findRefundById(refundId)
                .orElseThrow(() -> new IllegalStateException("退款单不存在: " + refundId));
        PaymentAttemptRecord attempt = paymentRepository
                .findLatestAttemptByPaymentNo(payment.paymentNo()).orElse(null);
        PaymentGateway.RefundResult result;
        try {
            result = paymentGateway.refund(payment, refund, false);
        } catch (RuntimeException e) {
            fail(refund, e.getClass().getSimpleName());
            return;
        }
        if (result == null || !result.success()) {
            fail(refund, result == null ? "渠道无响应" : safeMessage(result));
            return;
        }
        paymentRepository.markRefundSucceeded(refund.id(), result.providerRefundNo());
        metrics.refundSucceeded();
        long refunded = paymentRepository.sumRefundedCents(payment.paymentNo());
        if (refunded >= payment.amountCents()) {
            paymentOrderRepository.markRefunded(payment.paymentNo());
        }
        outboxRepository.append(new PaymentSettledEvent(
                PaymentEventTypes.REFUND_SUCCEEDED, payment.paymentNo(), payment.bizType(),
                payment.bizNo(), refund.amountCents(), payment.currency(), payment.channel(),
                result.providerRefundNo(), LocalDateTime.now()));
        log.info("payment.refund.succeeded paymentNo={} refundNo={} amount={} refunded={}",
                payment.paymentNo(), refund.refundNo(), refund.amountCents(), refunded);
        if (attempt != null) {
            paymentRepository.markAttemptSuccess(attempt.id());
        }
    }

    private void fail(RefundRecord refund, String message) {
        int attempts = refund.attemptCount() + 1;
        LocalDateTime nextRetryAt = attempts >= properties.safeRefundMaxAttempts()
                ? null
                : LocalDateTime.now().plusSeconds(
                        properties.safeRefundRetryBaseSeconds() * (long) Math.pow(2, attempts - 1));
        paymentRepository.markRefundFailed(refund.id(), limit(message, 500), nextRetryAt);
        metrics.refundFailed();
        log.warn("payment.refund.failed refundNo={} attempt={} retryAt={} message={}",
                refund.refundNo(), attempts, nextRetryAt, message);
    }

    /** 剧本：让指定退款下一次渠道调用失败（仅 Mock 测试用）。 */
    public void failNextAttempt(String refundNo, String message) {
        paymentRepository.findRefundByNo(refundNo)
                .ifPresent(refund -> fail(refund, message));
    }

    /** 重试到期退款（由 @Scheduled 任务调用）。 */
    @Transactional
    public int retryDue() {
        List<RefundRecord> due = paymentRepository.findRetryableRefunds(
                properties.safeRefundMaxAttempts(), properties.reconcileBatchSize());
        int retried = 0;
        for (RefundRecord refund : due) {
            if (paymentRepository.retryRefund(refund.id()) == 0) {
                continue;
            }
            PaymentOrderView payment = paymentOrderRepository
                    .findByPaymentNo(refund.paymentNo() == null ? "" : refund.paymentNo())
                    .orElse(null);
            if (payment == null) {
                fail(refund, "支付单不存在");
                continue;
            }
            Long previousTenant = TenantContext.currentOrNull();
            TenantContext.set(payment.tenantId());
            try {
                execute(refund.id(), payment);
            } finally {
                TenantContext.set(previousTenant);
            }
            retried++;
        }
        return retried;
    }

    @Override
    public Optional<RefundView> findByNo(String refundNo) {
        return paymentRepository.findRefundByNo(refundNo).map(this::toView);
    }

    @Override
    public List<RefundView> listByPayment(String paymentNo) {
        return paymentRepository.listRefundsByPayment(paymentNo).stream().map(this::toView).toList();
    }

    @Override
    public long refundedCents(String paymentNo) {
        return paymentRepository.sumRefundedCents(paymentNo);
    }

    private void validate(RefundCommand command) {
        if (command == null || command.paymentNo() == null || command.paymentNo().isBlank()) {
            throw BizException.badRequest("paymentNo 不能为空");
        }
        if (command.amountCents() <= 0) {
            throw BizException.badRequest("退款金额必须大于 0");
        }
        if (command.reason() == null || command.reason().isBlank()) {
            throw BizException.badRequest("退款原因不能为空");
        }
    }

    private RefundView toView(RefundRecord refund) {
        PaymentOrderView payment = refund.paymentNo() == null
                ? null : paymentOrderRepository.findByPaymentNo(refund.paymentNo()).orElse(null);
        return new RefundView(refund.refundNo(), refund.paymentNo(),
                payment == null ? null : payment.bizNo(), refund.amountCents(), refund.status(),
                refund.reason(), refund.providerRefundNo(), refund.errorMessage(),
                refund.requestedAt(), refund.succeededAt());
    }

    private String safeMessage(PaymentGateway.RefundResult result) {
        return result.message() == null ? "渠道退款失败" : result.message();
    }

    private String limit(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
