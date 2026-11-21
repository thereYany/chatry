package com.wherelee.base.modules.contracts.payment;

import java.time.LocalDateTime;

/**
 * 支付异常巡检所需的只读查询契约。
 *
 * <p>operations.alert 只依赖本接口做告警统计，不直接接触 payment 的 Repository 与配置。</p>
 */
public interface PaymentAlertQueries {

    /** 已支付但结算事件尚未成功投递给业务侧（发件箱未 SENT）的支付单数。 */
    long countSettlementUndeliveredBefore(LocalDateTime before);

    /** 长时间停留在 PAYING 的支付单数（渠道长时间未确认）。 */
    long countPayingPaymentBefore(LocalDateTime before);

    long countRefundRetryExhausted();
}
