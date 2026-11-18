package com.echomind.saas.commerce.domain;

import java.time.Instant;

public record SubscriptionSummary(
        String subscriptionId,
        String planId,
        String status,
        Instant startedAt,
        Instant currentPeriodStart,
        Instant currentPeriodEnd
) {
}
