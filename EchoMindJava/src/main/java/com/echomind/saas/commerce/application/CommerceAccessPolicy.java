package com.echomind.saas.commerce.application;

import com.echomind.saas.api.error.ResourceNotFoundException;
import com.echomind.saas.commerce.domain.CustomerId;
import com.echomind.saas.identity.domain.ActorScope;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class CommerceAccessPolicy {

    public void requireCustomer(ActorScope actor, CustomerId customerId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(customerId, "customerId");
        if (!actor.customerIds().contains(customerId.value())) {
            throw new ResourceNotFoundException();
        }
    }
}
