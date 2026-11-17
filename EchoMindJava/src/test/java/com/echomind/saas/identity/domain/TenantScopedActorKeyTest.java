package com.echomind.saas.identity.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TenantScopedActorKeyTest {

    @Test
    void includesTenantAndUserInTheLegacyCacheKey() {
        UserId userId = new UserId("user-1");

        TenantScopedActorKey tenantA = new TenantScopedActorKey(new TenantId("tenant-a"), userId);
        TenantScopedActorKey tenantB = new TenantScopedActorKey(new TenantId("tenant-b"), userId);

        assertThat(tenantA.value()).isEqualTo("tenant:tenant-a/user:user-1");
        assertThat(tenantA.value()).isNotEqualTo(tenantB.value());
    }
}
