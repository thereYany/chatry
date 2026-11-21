package com.wherelee.base.modules.contracts.payment;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 与业务无关的支付能力契约。
 *
 * <p>任何垂直域（订单、资料包、会员……）都可以用 bizType + bizNo 发起支付，
 * 支付单/尝试/回调/查单/发件箱全部由支付域负责；业务侧只需要监听
 * {@code PaymentSettledListener} 完成自己的履约。</p>
 */
public interface PaymentOperations {

    /**
     * 幂等发起支付：同一 (bizType, bizNo) 只会有一张支付单。
     * 已支付/已关闭的支付单直接返回当前状态，不再创建尝试。
     */
    InitiateResult initiate(InitiateCommand command);

    Optional<PaymentView> findByBiz(String bizType, String bizNo);

    /**
     * @param paymentNo 可空；业务方一般不需要指定，历史迁移单为兼容旧单号会传入
     */
    record InitiateCommand(
            String paymentNo,
            String bizType,
            String bizNo,
            long amountCents,
            String currency,
            String subject,
            LocalDateTime expireAt
    ) {
    }

    record InitiateResult(
            String paymentNo,
            String status,
            String channel,
            String cashierUrl
    ) {
    }

    record PaymentView(
            String paymentNo,
            String bizType,
            String bizNo,
            String status,
            long amountCents,
            String currency,
            String channel,
            String providerTradeNo,
            LocalDateTime paidAt
    ) {
    }
}
