package com.echomind.saas.commerce.api;

import com.echomind.saas.commerce.application.CommerceQuery;
import com.echomind.saas.commerce.domain.CustomerId;
import com.echomind.saas.identity.domain.ActorScope;
import java.util.Objects;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/commerce")
public final class CommerceController {

    private final CommerceQuery commerceQuery;

    public CommerceController(CommerceQuery commerceQuery) {
        this.commerceQuery = Objects.requireNonNull(commerceQuery, "commerceQuery");
    }

    @GetMapping("/customers/{customerId}")
    public CommerceResponse get(
            @PathVariable String customerId,
            @AuthenticationPrincipal ActorScope actorScope) {
        return CommerceResponse.from(
                commerceQuery.load(new CustomerId(customerId), actorScope));
    }
}
