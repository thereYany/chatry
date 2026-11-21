package com.wherelee.base.modules.contracts.metering;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record InvoiceView(
        String invoiceNo,
        SubjectRef subject,
        Instant from,
        Instant to,
        String currency,
        BigDecimal totalAmount,
        String status,
        List<InvoiceItemView> items
) {
}
