package com.echomind.saas.platform.idempotency;

import java.time.Instant;

/** A completed, tenant-scoped idempotent response ready for replay. */
public record IdempotencyReplay(
        String responseJson,
        Instant completedAt
) {
}
