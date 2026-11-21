package com.wherelee.base.modules.contracts.metering;

import java.time.Instant;

public record InvoiceCommand(SubjectRef subject, Instant from, Instant to, String currency) {
}
