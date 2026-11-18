package com.echomind.saas.commerce.application;

import com.echomind.saas.commerce.domain.CommercialSnapshot;
import com.echomind.saas.commerce.domain.CustomerId;
import com.echomind.saas.identity.domain.TenantId;
import java.util.Optional;

public interface CommerceReadRepository {
    Optional<CommercialSnapshot> load(TenantId tenantId, CustomerId customerId);
}
