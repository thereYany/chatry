package com.wherelee.base.modules.contracts.metering;

import java.math.BigDecimal;

public record ReservationView(String reservationId, String status, BigDecimal reservedAmount, String currency) {
}
