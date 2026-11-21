package com.wherelee.base.modules.payment.mock;

import com.wherelee.base.modules.payment.MockSignatureService;
import com.wherelee.base.modules.payment.PaymentCallbackService;
import com.wherelee.base.modules.payment.PaymentConfirmationService;
import com.wherelee.base.modules.payment.PaymentGateway;
import com.wherelee.base.modules.payment.PaymentNotification;
import com.wherelee.base.modules.payment.PaymentOrderRepository;
import com.wherelee.base.modules.payment.PaymentOrderView;
import com.wherelee.base.modules.payment.PaymentRepository;
import com.wherelee.base.modules.payment.PaymentStatus;
import com.wherelee.base.modules.platform.common.BizException;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Mock 收银台：模拟渠道行为，但**走真实的验签与确认链路**。
 *
 * <p>与旧实现的区别：以前 chatry 直接调 {@link PaymentConfirmationService#accept}，
 * 相当于「自己宣布支付成功」；现在先生成渠道流水与签名通知，再由回调入口
 * {@link PaymentCallbackService#acceptVerified} 验签后确认——签名错误、金额不符会被真实拒绝。</p>
 */
@Service
public class MockCashierService {

    private static final Logger log = LoggerFactory.getLogger(MockCashierService.class);
    private static final String PROVIDER = "MOCK";

    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final MockSignatureService signatureService;
    private final PaymentCallbackService callbackService;
    private final MockScenarioRepository scenarios;
    private final ScheduledExecutorService delayedExecutor = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "mock-delayed-callback");
        thread.setDaemon(true);
        return thread;
    });

    public MockCashierService(PaymentOrderRepository paymentOrderRepository,
                              PaymentRepository paymentRepository,
                              PaymentGateway paymentGateway,
                              MockSignatureService signatureService,
                              PaymentCallbackService callbackService,
                              MockScenarioRepository scenarios) {
        this.paymentOrderRepository = paymentOrderRepository;
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.signatureService = signatureService;
        this.callbackService = callbackService;
        this.scenarios = scenarios;
    }

    /** 按支付单执行剧本，返回本次回调的确认结果（延迟场景返回 null）。 */
    public PaymentConfirmationService.ConfirmationResult simulate(String paymentNo) {
        PaymentOrderView payment = paymentOrderRepository.findByPaymentNo(paymentNo)
                .orElseThrow(() -> BizException.notFound("支付单不存在"));
        Long previous = TenantContext.currentOrNull();
        TenantContext.set(payment.tenantId());
        try {
            MockScenarioRepository.MockScenarioRow row = scenarios.find(paymentNo).orElse(null);
            MockScenario scenario = row == null ? MockScenario.SUCCESS : parse(row.scenario());
            int delayMs = row == null ? 0 : row.delayMs();
            return runScenario(payment, scenario, delayMs);
        } finally {
            TenantContext.set(previous);
        }
    }

    private PaymentConfirmationService.ConfirmationResult runScenario(PaymentOrderView payment,
                                                                      MockScenario scenario,
                                                                      int delayMs) {
        switch (scenario) {
            case FAILED -> {
                markChannelAttempt(payment, false, payment.amountCents());
                return deliver(sign(payment, "PAYMENT_FAILED", payment.amountCents()));
            }
            case AMOUNT_MISMATCH -> {
                markChannelAttempt(payment, true, payment.amountCents() + 1);
                return deliver(sign(payment, "PAYMENT_SUCCESS", payment.amountCents() + 1));
            }
            case BAD_SIGNATURE -> {
                markChannelAttempt(payment, true, payment.amountCents());
                PaymentNotification signed = sign(payment, "PAYMENT_SUCCESS", payment.amountCents());
                return deliver(new PaymentNotification(signed.provider(), signed.eventKey(),
                        signed.orderNo(), signed.eventType(), signed.amountCents(), signed.tradeNo(),
                        "deadbeef" + signed.signature()));
            }
            case AFTER_CLOSE -> {
                markChannelAttempt(payment, true, payment.amountCents());
                return deliver(sign(payment, "PAYMENT_SUCCESS", payment.amountCents()));
            }
            case DUPLICATE -> {
                markChannelAttempt(payment, true, payment.amountCents());
                PaymentNotification signed = sign(payment, "PAYMENT_SUCCESS", payment.amountCents());
                deliver(signed);
                return deliver(signed);
            }
            case DELAYED -> {
                markChannelAttempt(payment, true, payment.amountCents());
                PaymentNotification signed = sign(payment, "PAYMENT_SUCCESS", payment.amountCents());
                delayedExecutor.schedule(() -> {
                    try {
                        deliver(signed);
                        log.info("mock.callback.delayed.delivered paymentNo={} delayMs={}",
                                payment.paymentNo(), delayMs);
                    } catch (RuntimeException e) {
                        log.warn("mock.callback.delayed.failed paymentNo={}", payment.paymentNo(), e);
                    }
                }, Math.max(1, delayMs), TimeUnit.MILLISECONDS);
                return null;
            }
            case QUERY_ONLY -> {
                // 只写渠道侧事实，不回调：验证主动查单兜底
                markChannelAttempt(payment, true, payment.amountCents());
                return null;
            }
            case SUCCESS -> {
                markChannelAttempt(payment, true, payment.amountCents());
                return deliver(sign(payment, "PAYMENT_SUCCESS", payment.amountCents()));
            }
            default -> throw BizException.badRequest("未知 Mock 场景：" + scenario);
        }
    }

    /** 机器人可读的「渠道已扣款」事实：写支付尝试，供查单/对账读取。 */
    private void markChannelAttempt(PaymentOrderView payment, boolean success, long amountCents) {
        var attempt = paymentRepository.findLatestAttemptByPaymentNo(payment.paymentNo()).orElse(null);
        if (attempt == null) {
            return;
        }
        paymentRepository.updateProviderFact(attempt.id(), success ? "SUCCESS" : "FAILED",
                amountCents, tradeNo(payment));
    }

    private PaymentNotification sign(PaymentOrderView payment, String eventType, long amountCents) {
        PaymentNotification unsigned = new PaymentNotification(PROVIDER,
                "MOCK-EVT-" + payment.paymentNo() + "-" + eventType,
                payment.bizNo(), eventType, amountCents, tradeNo(payment), null);
        return new PaymentNotification(unsigned.provider(), unsigned.eventKey(), unsigned.orderNo(),
                unsigned.eventType(), unsigned.amountCents(), unsigned.tradeNo(),
                signatureService.sign(unsigned));
    }

    /** 回调入口：先验签，再走真实确认链路。 */
    private PaymentConfirmationService.ConfirmationResult deliver(PaymentNotification notification) {
        if (!signatureService.verify(notification)) {
            throw new BizException(HttpStatus.UNAUTHORIZED, "Mock 回调签名校验失败");
        }
        return callbackService.acceptVerified(notification);
    }

    private String tradeNo(PaymentOrderView payment) {
        return "MOCK-" + payment.bizNo() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private MockScenario parse(String value) {
        try {
            return MockScenario.valueOf(value);
        } catch (IllegalArgumentException e) {
            return MockScenario.SUCCESS;
        }
    }
}
