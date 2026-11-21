package com.wherelee.base.modules.contracts.metering;

import java.math.BigDecimal;

public record BalanceView(SubjectRef subject, BigDecimal balance, BigDecimal frozen, String currency) {
}
