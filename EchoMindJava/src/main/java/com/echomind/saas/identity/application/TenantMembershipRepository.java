package com.echomind.saas.identity.application;

import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.UserId;
import java.util.Set;

public interface TenantMembershipRepository {
    Set<ActorRole> findRoles(TenantId tenantId, UserId userId);

    Set<String> findCustomerIds(TenantId tenantId, UserId userId);
}
