package com.wherelee.chatry.mall;

import java.math.BigDecimal;

/** 模拟支付结果：订单状态 + 最新额度余额。 */
public record PayResponse(String orderNo, String status, BigDecimal balance) {
}
