package com.echomind.saas.rag.application;

import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.domain.TenantId;
import java.util.Objects;
import java.util.Set;

public record KnowledgeSearchScope(
        TenantId tenantId,
        boolean tenantWide,
        Set<String> customerIds
) {
    public KnowledgeSearchScope {
        tenantId = Objects.requireNonNull(tenantId, "tenantId");
        customerIds = Set.copyOf(Objects.requireNonNull(customerIds, "customerIds"));
    }

    public static KnowledgeSearchScope from(ActorScope actor) {
        Objects.requireNonNull(actor, "actor");
        boolean tenantWide = actor.hasRole(ActorRole.SUPPORT_AGENT)
                || actor.hasRole(ActorRole.TENANT_ADMIN);
        return new KnowledgeSearchScope(
                actor.tenantId(),
                tenantWide,
                actor.customerIds());
    }
}
