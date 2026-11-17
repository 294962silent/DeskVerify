package com.echomind.saas.platform.outbox;

import com.echomind.saas.identity.domain.TenantId;
import java.time.Instant;

public record OutboxMessage(
        String id,
        TenantId tenantId,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payloadJson,
        Instant availableAt
) {
}
