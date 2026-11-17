package com.echomind.saas.identity.security;

import com.echomind.saas.identity.domain.ActorScope;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public final class ActorScopeAuthentication extends AbstractAuthenticationToken {

    private final ActorScope scope;

    public ActorScopeAuthentication(ActorScope scope) {
        super(List.copyOf(scope.roles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList()));
        this.scope = scope;
        setAuthenticated(true);
    }

    public ActorScope getScope() {
        return scope;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return scope;
    }

    @Override
    public String getName() {
        return scope.userId().value();
    }
}
