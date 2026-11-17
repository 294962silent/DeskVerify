package com.echomind.saas.identity.application;

import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.UserId;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TenantAccessServiceTest {

    private final TenantMembershipRepository repository = mock(TenantMembershipRepository.class);
    private final TenantAccessService service = new TenantAccessService(repository);

    @Test
    void deniesAccessWhenTheUserHasNoActiveTenantRoles() {
        UserId userId = new UserId("user-1");
        TenantId tenantId = new TenantId("tenant-a");
        when(repository.findRoles(tenantId, userId)).thenReturn(Set.of());

        assertThatThrownBy(() -> service.resolve(userId, tenantId, "request-1"))
                .isInstanceOf(TenantAccessDeniedException.class)
                .hasMessage("active tenant membership is required");
        verify(repository).findRoles(tenantId, userId);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void resolvesTheExactActorScopeForAValidRole() {
        UserId userId = new UserId("user-1");
        TenantId tenantId = new TenantId("tenant-a");
        when(repository.findRoles(tenantId, userId)).thenReturn(Set.of(ActorRole.SUPPORT_AGENT));
        when(repository.findCustomerIds(tenantId, userId))
                .thenReturn(Set.of("customer-1", "customer-2"));

        ActorScope scope = service.resolve(userId, tenantId, "request-42");

        assertThat(scope.userId()).isEqualTo(userId);
        assertThat(scope.tenantId()).isEqualTo(tenantId);
        assertThat(scope.roles()).containsExactly(ActorRole.SUPPORT_AGENT);
        assertThat(scope.customerIds()).containsExactlyInAnyOrder("customer-1", "customer-2");
        assertThat(scope.requestId()).isEqualTo("request-42");
        verify(repository).findRoles(tenantId, userId);
        verify(repository).findCustomerIds(tenantId, userId);
    }
}
