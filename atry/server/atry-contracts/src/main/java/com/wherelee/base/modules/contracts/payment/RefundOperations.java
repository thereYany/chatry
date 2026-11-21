package com.wherelee.base.modules.contracts.payment;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 退款契约（纯支付域）：业务侧只发起退款、查询结果，不关心渠道细节。
 *
 * <p>退款的资金/额度冲销由业务侧消费 {@code REFUND_SUCCEEDED} 事件完成。</p>
 */
public interface RefundOperations {

    /**
     * 发起退款（幂等：同一 refundNo 重复调用返回同一退款单）。
     *
     * <p>金额校验：已退 + 本次 <= 支付单金额。支付单必须是 PAID/REFUNDING。</p>
     */
    RefundView request(RefundCommand command);

    Optional<RefundView> findByNo(String refundNo);

    List<RefundView> listByPayment(String paymentNo);

    /** 已退款总额（成功 + 处理中）。 */
    long refundedCents(String paymentNo);

    record RefundCommand(String refundNo, String paymentNo, long amountCents, String reason,
                         Long requestedBy) {
    }

    record RefundView(String refundNo, String paymentNo, String bizNo, long amountCents,
                      String status, String reason, String providerRefundNo,
                      String errorMessage, LocalDateTime requestedAt, LocalDateTime succeededAt) {
    }
}
