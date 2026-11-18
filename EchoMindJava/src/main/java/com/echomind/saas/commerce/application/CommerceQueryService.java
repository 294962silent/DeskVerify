package com.echomind.saas.commerce.application;

import com.echomind.saas.api.error.ResourceNotFoundException;
import com.echomind.saas.commerce.domain.CommercialSnapshot;
import com.echomind.saas.commerce.domain.CustomerId;
import com.echomind.saas.identity.domain.ActorScope;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public final class CommerceQueryService implements CommerceQuery {

    private final CommerceReadRepository repository;
    private final CommerceAccessPolicy accessPolicy;

    public CommerceQueryService(
            CommerceReadRepository repository,
            CommerceAccessPolicy accessPolicy) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    }

    @Override
    public CommercialSnapshot load(CustomerId customerId, ActorScope actor) {
        accessPolicy.requireCustomer(actor, customerId);
        return repository.load(actor.tenantId(), customerId)
                .orElseThrow(ResourceNotFoundException::new);
    }
}
