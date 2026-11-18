package com.echomind.saas.commerce.domain;

import java.time.Instant;

public record PaymentAttemptSummary(
        String paymentAttemptId,
        String invoiceId,
        String status,
        Money amount,
        Instant attemptedAt,
        String failureCode
) {
}
