package com.echomind.saas.platform.idempotency;

import com.echomind.saas.identity.domain.TenantId;
import java.time.Instant;
import java.util.Optional;

public interface IdempotencyRepository {
    /**
     * Outcomes are: a new acquisition or expired reclaim returns a fencing token;
     * an active duplicate with the same hash returns empty; a completed record is
     * available through {@link #findCompleted(TenantId, String)}; a reused key
     * with another hash throws a conflict; an expired record may be reclaimed.
     */
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

    Optional<IdempotencyReplay> findCompleted(TenantId tenantId, String key);
}
