package com.wherelee.base.modules.payment;

/**
 * 支付渠道 SPI（策略）。
 *
 * <p>渠道适配器只接受支付域自己的 PaymentOrderView，不认识任何业务概念。</p>
 */
public interface PaymentGateway {

    String channel();

    /** 商户侧渠道单号：同一支付单同一尝试幂等。 */
    String outTradeNo(PaymentOrderView order, int attemptNo);

    PaymentAction initiate(PaymentOrderView order, PaymentAttemptRecord attempt);

    PaymentQueryResult query(PaymentOrderView order, PaymentAttemptRecord attempt);

    /**
     * 关闭渠道侧未支付的交易（可选能力）。
     *
     * <p>默认实现表示渠道不支持主动关单：调用方可以退化为只在本地关闭支付单。
     * 渠道返回“交易不存在”时用 {@link CloseResult#notFound(String)} 表达——这在关单语义上等价于已关闭。</p>
     */
    default CloseResult close(PaymentOrderView order, PaymentAttemptRecord attempt) {
        // 渠道没有关单能力时按“渠道无单”处理，调用方可以直接本地关单（保持旧行为）。
        return CloseResult.notFound("渠道未实现关单，按本地关单处理");
    }

    RefundResult refund(PaymentOrderView order, RefundRecord refund, boolean simulateFailure);

    record PaymentAction(String cashierUrl) {
    }

    record PaymentQueryResult(String status, long amountCents, String tradeNo, String message) {
    }

    record CloseResult(boolean closed, boolean notFound, String message) {
        public static CloseResult closedOk() {
            return new CloseResult(true, false, null);
        }

        public static CloseResult notFound(String message) {
            return new CloseResult(false, true, message);
        }

        public static CloseResult failed(String message) {
            return new CloseResult(false, false, message);
        }
    }

    record RefundResult(boolean success, String providerRefundNo, String message) {
    }
}
