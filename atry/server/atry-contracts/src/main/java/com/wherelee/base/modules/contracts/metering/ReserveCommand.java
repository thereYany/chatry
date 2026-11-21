package com.wherelee.base.modules.contracts.metering;

import java.math.BigDecimal;
import java.time.Instant;

/** 预扣请求（准入阶段使用；expiresAt 到期由回收任务释放）。 */
public record ReserveCommand(
        String reservationId,
        SubjectRef subject,
        BigDecimal amount,
        String currency,
        Instant expiresAt,
        String bizRef
) {
}
