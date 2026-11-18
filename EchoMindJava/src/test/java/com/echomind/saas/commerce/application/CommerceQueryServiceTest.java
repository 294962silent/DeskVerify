package com.echomind.saas.commerce.application;

import com.echomind.saas.api.error.ResourceNotFoundException;
import com.echomind.saas.commerce.domain.CommercialSnapshot;
import com.echomind.saas.commerce.domain.CustomerId;
import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.UserId;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CommerceQueryServiceTest {

    private final CommerceReadRepository repository = mock(CommerceReadRepository.class);
    private final CommerceQueryService service =
            new CommerceQueryService(repository, new CommerceAccessPolicy());

    @Test
    void deniesCustomerOutsideActorScopeAsNotFound() {
        ActorScope actor = actor("tenant-a", Set.of(ActorRole.CUSTOMER), Set.of("customer-a"));

        assertThatThrownBy(() -> service.load(new CustomerId("customer-b"), actor))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("resource not found");
        verifyNoInteractions(repository);
    }

    @Test
    void usesTenantFromActorScope() {
        ActorScope actor = actor("tenant-a", Set.of(ActorRole.CUSTOMER), Set.of("customer-a"));
        TenantId tenantId = new TenantId("tenant-a");
        CustomerId customerId = new CustomerId("customer-a");
        CommercialSnapshot snapshot = mock(CommercialSnapshot.class);
        when(repository.load(tenantId, customerId)).thenReturn(Optional.of(snapshot));

        assertThat(service.load(customerId, actor)).isSameAs(snapshot);

        verify(repository).load(tenantId, customerId);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void hidesMissingRepositoryDataAsTheSameNotFound() {
        ActorScope actor = actor("tenant-a", Set.of(ActorRole.CUSTOMER), Set.of("customer-a"));
        TenantId tenantId = new TenantId("tenant-a");
        CustomerId customerId = new CustomerId("customer-a");
        when(repository.load(tenantId, customerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.load(customerId, actor))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("resource not found");
    }

    @Test
    void adminRoleDoesNotBypassCustomerMapping() {
        ActorScope actor = actor("tenant-a", Set.of(ActorRole.TENANT_ADMIN), Set.of());

        assertThatThrownBy(() -> service.load(new CustomerId("customer-a"), actor))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("resource not found");
        verifyNoInteractions(repository);
    }

    private ActorScope actor(String tenantId, Set<ActorRole> roles, Set<String> customerIds) {
        return new ActorScope(
                new UserId("user-1"),
                new TenantId(tenantId),
                roles,
                customerIds,
                "request-1");
    }
}
