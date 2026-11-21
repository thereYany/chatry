package com.wherelee.base.modules.contracts.metering;

import java.math.BigDecimal;

public record InvoiceItemView(String eventId, String meterKey, BigDecimal quantity, BigDecimal amount) {
}
