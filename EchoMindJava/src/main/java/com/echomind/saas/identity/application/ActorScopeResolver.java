package com.echomind.saas.identity.application;

import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.UserId;

public interface ActorScopeResolver {
    ActorScope resolve(UserId userId, TenantId tenantId, String requestId);
}
