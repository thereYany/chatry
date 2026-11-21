package com.wherelee.base.modules.payment;

import com.wherelee.base.modules.contracts.payment.PaymentOperations;
import com.wherelee.base.modules.platform.aop.DistributedLock;
import com.wherelee.base.modules.platform.common.BizException;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 通用支付门面：把"业务单 → 支付单 → 渠道"的流程收在一处，供任意垂直域复用。
 *
 * <p>任何垂直域都走同一个入口：新业务只需给 bizType/bizNo；历史迁移单可传入固定 paymentNo。</p>
 *
 * <p>事务边界：支付单/支付尝试的落库在本地事务内完成并先提交，渠道下单（HTTP）在事务外调用，
 * 避免"渠道已建单、本地事务回滚"导致回调成为未知单。</p>
 */
@Slf4j
@Service
public class PaymentFacade implements PaymentOperations {

    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final TransactionTemplate transactionTemplate;
    private final String appId;

    public PaymentFacade(PaymentOrderRepository paymentOrderRepository,
                         PaymentRepository paymentRepository,
                         PaymentGateway paymentGateway,
                         PlatformTransactionManager transactionManager,
                         @Value("${app.payment.app-id:atry}") String appId) {
        this.paymentOrderRepository = paymentOrderRepository;
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // 支付单/尝试必须独立提交：即使业务侧外层事务回滚，也不能丢掉渠道已建单的本地记录。
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.appId = appId;
    }

    @Override
    @DistributedLock(key = "payment:initiate", waitMs = 500, leaseMs = 10_000)
    public InitiateResult initiate(InitiateCommand command) {
        validate(command);
        Prepared prepared = transactionTemplate.execute(status -> prepare(command));
        if (prepared == null) {
            throw new IllegalStateException("支付单准备失败");
        }
        if (prepared.terminalResult() != null) {
            return prepared.terminalResult();
        }
        // 渠道下单在事务外执行：失败时本地支付单/尝试已提交，可由重试或后续查询兜底。
        PaymentGateway.PaymentAction action = paymentGateway.initiate(prepared.payment(), prepared.attempt());
        log.info("payment_initiated paymentNo={} bizType={} bizNo={} amountCents={}",
                prepared.payment().paymentNo(), prepared.payment().bizType(), prepared.payment().bizNo(),
                prepared.payment().amountCents());
        return new InitiateResult(prepared.payment().paymentNo(), "PAYING",
                paymentGateway.channel(), action.cashierUrl());
    }

    private Prepared prepare(InitiateCommand command) {
        String paymentNo = command.paymentNo() == null || command.paymentNo().isBlank()
                ? PaymentNo.issue()
                : command.paymentNo();
        long tenantId = TenantContext.current();
        PaymentOrderView view = new PaymentOrderView(
                paymentNo, command.bizNo(), appId, command.bizType(), command.subject(),
                command.amountCents(), command.currency(), "CREATED", paymentGateway.channel(),
                null, command.expireAt(), null, tenantId);
        paymentOrderRepository.insertIfAbsent(view, tenantId, LocalDateTime.now());

        PaymentOrderView payment = paymentOrderRepository
                .findByBiz(command.bizType(), command.bizNo())
                .orElseThrow(() -> new IllegalStateException("支付单创建后不可读取"));
        if ("PAID".equals(payment.status()) || "REFUNDED".equals(payment.status())
                || "CLOSED".equals(payment.status())) {
            return new Prepared(payment, null,
                    new InitiateResult(payment.paymentNo(), payment.status(),
                            payment.channel(), null));
        }

        PaymentAttemptRecord attempt = paymentRepository
                .findLatestAttemptByPaymentNo(payment.paymentNo()).orElse(null);
        if (attempt == null || !"PROCESSING".equals(attempt.status())) {
            int attemptNo = paymentRepository.nextAttemptNoByPaymentNo(payment.paymentNo());
            String tradeNo = paymentGateway.outTradeNo(payment, attemptNo);
            paymentRepository.createAttempt(null, payment.paymentNo(), attemptNo,
                    paymentGateway.channel(), tradeNo, payment.amountCents(),
                    payment.paymentNo() + "|" + attemptNo + "|" + payment.amountCents());
            attempt = paymentRepository.findLatestAttemptByPaymentNo(payment.paymentNo())
                    .orElseThrow(() -> new IllegalStateException("支付尝试创建后不可读取"));
        }
        paymentOrderRepository.markPaying(payment.paymentNo(), paymentGateway.channel(),
                attempt.providerTradeNo());
        PaymentOrderView payable = paymentOrderRepository.findByPaymentNo(payment.paymentNo())
                .orElse(payment);
        return new Prepared(payable, attempt, null);
    }

    @Override
    public Optional<PaymentView> findByBiz(String bizType, String bizNo) {
        return paymentOrderRepository.findByBiz(bizType, bizNo).map(payment -> new PaymentView(
                payment.paymentNo(), payment.bizType(), payment.bizNo(), payment.status(),
                payment.amountCents(), payment.currency(), payment.channel(),
                payment.providerTradeNo(), payment.paidAt()));
    }

    private void validate(InitiateCommand command) {
        if (command.bizType() == null || command.bizType().isBlank()
                || command.bizNo() == null || command.bizNo().isBlank()) {
            throw BizException.badRequest("bizType/bizNo 不能为空");
        }
        if (command.amountCents() <= 0) {
            throw BizException.badRequest("支付金额必须大于 0");
        }
        if (command.currency() == null || command.currency().isBlank()) {
            throw BizException.badRequest("币种不能为空");
        }
    }

    private record Prepared(PaymentOrderView payment, PaymentAttemptRecord attempt,
                            InitiateResult terminalResult) {
    }
}
