package com.wherelee.base.modules.contracts.metering;

import java.time.Instant;

public record UsageQuery(SubjectRef subject, Instant from, Instant to, int limit) {
}
