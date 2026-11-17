package com.echomind.saas.identity.domain;

import java.util.Objects;
import java.util.Set;

public record ActorScope(
        UserId userId,
        TenantId tenantId,
        Set<ActorRole> roles,
        Set<String> customerIds,
        String requestId
) {
    public ActorScope {
        userId = Objects.requireNonNull(userId, "userId");
        tenantId = Objects.requireNonNull(tenantId, "tenantId");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        customerIds = Set.copyOf(Objects.requireNonNull(customerIds, "customerIds"));
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId is required");
        }
    }

    public boolean hasRole(ActorRole role) {
        return roles.contains(role);
    }
}
