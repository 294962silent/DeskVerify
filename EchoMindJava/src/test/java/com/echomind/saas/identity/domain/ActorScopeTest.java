package com.echomind.saas.identity.domain;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActorScopeTest {

    @Test
    void createsAnImmutableTenantScopedActor() {
        ActorScope scope = new ActorScope(
                new UserId("user-1"),
                new TenantId("tenant-a"),
                Set.of(ActorRole.SUPPORT_AGENT),
                Set.of("customer-1"),
                "request-1"
        );

        assertThat(scope.hasRole(ActorRole.SUPPORT_AGENT)).isTrue();
        assertThatThrownBy(() -> scope.roles().add(ActorRole.TENANT_ADMIN))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsBlankActorIdentity() {
        assertThatThrownBy(() -> new UserId(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TenantId("tenant_a"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
