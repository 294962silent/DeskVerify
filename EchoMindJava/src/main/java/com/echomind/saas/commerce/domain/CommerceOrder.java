package com.echomind.saas.commerce.domain;

import java.time.Instant;

public record CommerceOrder(
        String orderId,
        String productCode,
        int quantity,
        Money amount,
        String status,
        Instant orderedAt,
        Instant fulfilledAt,
        String subscriptionId
) {
}
