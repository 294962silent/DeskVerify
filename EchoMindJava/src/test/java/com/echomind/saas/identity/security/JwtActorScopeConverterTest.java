package com.echomind.saas.identity.security;

import com.echomind.saas.identity.application.TenantAccessDeniedException;
import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.ActorScope;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtActorScopeConverterTest {

    private final JwtActorScopeConverter converter = new JwtActorScopeConverter(
            (userId, tenantId, requestId) -> new ActorScope(
                    userId,
                    tenantId,
                    Set.of(ActorRole.SUPPORT_AGENT),
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
        assertThat(authentication.getScope().hasRole(ActorRole.SUPPORT_AGENT)).isTrue();
        assertThat(authentication.getScope().requestId()).isEqualTo("jwt-1");
    }

    @Test
    void translatesMissingSubjectIntoAuthenticationFailure() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("tenant_id", "tenant-a")
                .build();

        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void translatesMalformedSubjectIntoAuthenticationFailure() {
        assertThatThrownBy(() -> converter.convert(jwtWithClaims("bad subject", "tenant-a")))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void translatesMissingTenantIntoAuthenticationFailure() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-1")
                .build();

        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void translatesMalformedTenantIntoAuthenticationFailure() {
        assertThatThrownBy(() -> converter.convert(jwtWithClaims("user-1", "TENANT_A")))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void translatesMembershipFailureIntoAuthenticationFailure() {
        JwtActorScopeConverter deniedConverter = new JwtActorScopeConverter(
                (userId, tenantId, requestId) -> {
                    throw new TenantAccessDeniedException("raw membership diagnostic");
                });

        assertThatThrownBy(() -> deniedConverter.convert(jwtWithClaims("user-1", "tenant-a")))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageNotContaining("raw membership diagnostic");
    }

    private static Jwt jwtWithClaims(String subject, String tenantId) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(subject)
                .claim("tenant_id", tenantId)
                .build();
    }
}
