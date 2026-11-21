package com.wherelee.base.modules.contracts.metering;

import java.math.BigDecimal;

/** 充值/调整入账（bizNo 幂等）。 */
public record RechargeCommand(SubjectRef subject, BigDecimal amount, String currency, String bizNo) {
}
