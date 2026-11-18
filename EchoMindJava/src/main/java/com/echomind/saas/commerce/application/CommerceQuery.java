package com.echomind.saas.commerce.application;

import com.echomind.saas.commerce.domain.CommercialSnapshot;
import com.echomind.saas.commerce.domain.CustomerId;
import com.echomind.saas.identity.domain.ActorScope;

public interface CommerceQuery {
    CommercialSnapshot load(CustomerId customerId, ActorScope actor);
}
