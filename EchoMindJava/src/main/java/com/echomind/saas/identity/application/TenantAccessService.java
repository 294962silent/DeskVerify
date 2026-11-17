package com.echomind.saas.identity.application;

import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.UserId;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public final class TenantAccessService implements ActorScopeResolver {

    private final TenantMembershipRepository repository;

    public TenantAccessService(TenantMembershipRepository repository) {
        this.repository = repository;
    }

    @Override
    public ActorScope resolve(UserId userId, TenantId tenantId, String requestId) {
        Set<ActorRole> roles = repository.findRoles(tenantId, userId);
        if (roles.isEmpty()) {
            throw new TenantAccessDeniedException("active tenant membership is required");
        }
        return new ActorScope(
                userId,
                tenantId,
                roles,
                repository.findCustomerIds(tenantId, userId),
                requestId
        );
    }
}
