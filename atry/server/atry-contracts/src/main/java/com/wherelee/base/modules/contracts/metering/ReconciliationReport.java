package com.wherelee.base.modules.contracts.metering;

import java.time.Instant;
import java.util.List;

public record ReconciliationReport(
        Instant from,
        Instant to,
        int checkedEvents,
        int checkedCharges,
        List<ReconDiff> diffs
) {
    public boolean balanced() {
        return diffs.isEmpty();
    }
}
