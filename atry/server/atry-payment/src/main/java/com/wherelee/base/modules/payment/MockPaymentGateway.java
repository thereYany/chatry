package com.wherelee.base.modules.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "mock", matchIfMissing = true)
public class MockPaymentGateway implements PaymentGateway {

    @Override
    public String channel() {
        return "MOCK";
    }

    @Override
    public String outTradeNo(PaymentOrderView order, int attemptNo) {
        return "MOCK-" + order.bizNo() + "-" + attemptNo;
    }

    @Override
    public PaymentAction initiate(PaymentOrderView order, PaymentAttemptRecord attempt) {
        return new PaymentAction("/mock-pay/" + order.bizNo());
    }

    @Override
    public PaymentQueryResult query(PaymentOrderView order, PaymentAttemptRecord attempt) {
        if (attempt == null) {
            return new PaymentQueryResult("PENDING", order.amountCents(), null, "尚无支付尝试");
        }
        // Mock 渠道的尝试状态用 SUCCESS/FAILED 词表，查单服务只认 PAID/FAILED/...；
        // 这里做词表归一，避免无回调场景下主动查单永远无法结算。
        String status = "SUCCESS".equals(attempt.providerStatus()) ? "PAID" : attempt.providerStatus();
        return new PaymentQueryResult(
                status,
                attempt.providerAmountCents(),
                attempt.providerTradeNo(),
                null
        );
    }

    @Override
    public CloseResult close(PaymentOrderView order, PaymentAttemptRecord attempt) {
        return CloseResult.closedOk();
    }

    @Override
    public RefundResult refund(PaymentOrderView order, RefundRecord refund, boolean simulateFailure) {
        if (simulateFailure) {
            return new RefundResult(false, null, "Mock 退款失败：模拟网关异常");
        }
        return new RefundResult(true, "MOCKRF-" + refund.refundNo(), null);
    }
}
