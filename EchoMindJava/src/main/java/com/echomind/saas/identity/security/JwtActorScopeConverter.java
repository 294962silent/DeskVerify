package com.echomind.saas.identity.security;

import com.echomind.saas.identity.application.ActorScopeResolver;
import com.echomind.saas.identity.application.TenantAccessDeniedException;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.UserId;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;

public final class JwtActorScopeConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private final ActorScopeResolver actorScopeResolver;

    public JwtActorScopeConverter(ActorScopeResolver actorScopeResolver) {
        this.actorScopeResolver = actorScopeResolver;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            UserId userId = new UserId(jwt.getSubject());
            TenantId tenantId = new TenantId(jwt.getClaimAsString("tenant_id"));
            String requestId = jwt.getId() == null || jwt.getId().isBlank()
                    ? java.util.UUID.randomUUID().toString()
                    : jwt.getId();
            ActorScope scope = actorScopeResolver.resolve(userId, tenantId, requestId);
            if (scope == null) {
                throw new IllegalArgumentException("actor scope resolution returned no scope");
            }
            return new ActorScopeAuthentication(scope);
        } catch (IllegalArgumentException | TenantAccessDeniedException exception) {
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_token"));
        }
    }
}
