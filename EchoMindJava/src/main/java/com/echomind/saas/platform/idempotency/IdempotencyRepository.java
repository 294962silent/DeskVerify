package com.echomind.saas.platform.idempotency;

import com.echomind.saas.identity.domain.TenantId;
import java.time.Instant;
import java.util.Optional;

public interface IdempotencyRepository {
    Optional<String> tryAcquire(
            TenantId tenantId,
            String key,
            String requestHash,
            Instant expiresAt
    );

    void complete(
            TenantId tenantId,
            String key,
            String attemptToken,
            String responseJson
    );
}
