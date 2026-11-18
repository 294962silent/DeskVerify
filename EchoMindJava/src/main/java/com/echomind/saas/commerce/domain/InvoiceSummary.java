package com.echomind.saas.commerce.domain;

import java.time.Instant;

public record InvoiceSummary(
        String invoiceId,
        String status,
        Money amount,
        Instant issuedAt,
        Instant dueAt,
        Instant paidAt
) {
}
