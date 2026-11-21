package com.wherelee.base.modules.contracts.metering;

import java.math.BigDecimal;

/** 按实际用量结算预扣（settle + release = reserved）。 */
public record SettleCommand(
        String reservationId,
        BigDecimal actualAmount,
        String currency,
        String bizNo
) {
}
