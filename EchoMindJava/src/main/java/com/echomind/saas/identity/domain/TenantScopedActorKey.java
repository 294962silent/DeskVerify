package com.echomind.saas.identity.domain;

import java.util.Objects;

public record TenantScopedActorKey(TenantId tenantId, UserId userId) {
    public TenantScopedActorKey {
        tenantId = Objects.requireNonNull(tenantId, "tenantId");
        userId = Objects.requireNonNull(userId, "userId");
    }

    public String value() {
        return "tenant:" + tenantId.value() + "/user:" + userId.value();
    }
}
