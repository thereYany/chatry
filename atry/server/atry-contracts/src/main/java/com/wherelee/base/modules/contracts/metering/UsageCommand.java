package com.wherelee.base.modules.contracts.metering;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** 一次用量上报（幂等键 eventId 由调用方确定性生成）。 */
public record UsageCommand(
        String eventId,
        String meterKey,
        SubjectRef subject,
        BigDecimal quantity,
        String unit,
        Map<String, String> dimensions,
        Instant occurredAt,
        String sourceRef
) {
}
