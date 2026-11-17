package com.echomind.saas.platform.idempotency;

import com.echomind.saas.identity.domain.TenantId;
import java.time.Instant;

public interface IdempotencyRepository {
    boolean tryAcquire(TenantId tenantId, String key, String requestHash, Instant expiresAt);

    void complete(TenantId tenantId, String key, String responseJson);
}
