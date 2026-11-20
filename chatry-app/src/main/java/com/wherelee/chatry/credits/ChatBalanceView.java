package com.wherelee.chatry.credits;

import java.math.BigDecimal;

/** 余额视图（不向垂直前端暴露 tenant/app 维度）。 */
public record ChatBalanceView(BigDecimal balance, BigDecimal frozen, String currency) {
}
