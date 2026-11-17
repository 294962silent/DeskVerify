package com.echomind.saas.identity.security;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class JwtActorScopeConverterTest {

    private final JwtActorScopeConverter converter = new JwtActorScopeConverter(
            (userId, tenantId, requestId) -> new com.echomind.saas.identity.domain.ActorScope(
                    userId,
                    tenantId,
                    Set.of(com.echomind.saas.identity.domain.ActorRole.SUPPORT_AGENT),
                    Set.of("customer-1"),
                    requestId
            ));

    @Test
    void convertsTrustedClaimsIntoActorScope() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-1")
                .claim("jti", "jwt-1")
                .claim("tenant_id", "tenant-a")
                .issuedAt(Instant.parse("2026-11-17T00:00:00Z"))
                .expiresAt(Instant.parse("2026-11-17T01:00:00Z"))
                .build();

        ActorScopeAuthentication authentication =
                (ActorScopeAuthentication) converter.convert(jwt);

        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getScope().tenantId().value()).isEqualTo("tenant-a");
        assertThat(authentication.getScope().hasRole(
                com.echomind.saas.identity.domain.ActorRole.SUPPORT_AGENT)).isTrue();
        assertThat(authentication.getScope().requestId()).isEqualTo("jwt-1");
    }
}
