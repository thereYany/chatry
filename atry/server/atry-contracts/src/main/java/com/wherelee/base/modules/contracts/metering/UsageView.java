package com.wherelee.base.modules.contracts.metering;

import java.math.BigDecimal;
import java.time.Instant;

public record UsageView(String eventId, String meterKey, BigDecimal quantity, String unit,
                        String dimensionsJson, Instant occurredAt) {
}
