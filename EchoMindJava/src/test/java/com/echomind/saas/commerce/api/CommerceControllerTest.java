package com.echomind.saas.commerce.api;

import com.echomind.config.AppConfig;
import com.echomind.saas.api.error.ApiAccessDeniedHandler;
import com.echomind.saas.api.error.ApiAuthenticationEntryPoint;
import com.echomind.saas.commerce.application.CommerceAccessPolicy;
import com.echomind.saas.commerce.application.CommerceQueryService;
import com.echomind.saas.commerce.application.CommerceReadRepository;
import com.echomind.saas.commerce.domain.CommerceOrder;
import com.echomind.saas.commerce.domain.CommercialSnapshot;
import com.echomind.saas.commerce.domain.CustomerAccount;
import com.echomind.saas.commerce.domain.CustomerId;
import com.echomind.saas.commerce.domain.Entitlement;
import com.echomind.saas.commerce.domain.InvoiceSummary;
import com.echomind.saas.commerce.domain.Money;
import com.echomind.saas.commerce.domain.PaymentAttemptSummary;
import com.echomind.saas.commerce.domain.PlanSummary;
import com.echomind.saas.commerce.domain.SubscriptionSummary;
import com.echomind.saas.identity.application.ActorScopeResolver;
import com.echomind.saas.identity.application.TenantAccessDeniedException;
import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.security.SaasSecurityConfiguration;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CommerceController.class)
@Import({
        AppConfig.class,
        SaasSecurityConfiguration.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        CommerceQueryService.class,
        CommerceAccessPolicy.class,
        CommerceControllerTest.TestActorScopeConfiguration.class
})
@TestPropertySource(properties =
        "echomind.security.jwt.hmac-secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class CommerceControllerTest {

    private static final String TEST_SECRET =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CommerceReadRepository repository;

    @Test
    void returnsCompleteSnapshotUsingAuthenticatedActor() throws Exception {
        CommercialSnapshot snapshot = completeSnapshot("tenant-a", "Acme");
        when(repository.load(new TenantId("tenant-a"), new CustomerId("customer-1")))
                .thenReturn(Optional.of(snapshot));

        String body = mvc.perform(get("/api/v1/commerce/customers/customer-1")
                        .param("tenant_id", "tenant-b")
                        .header("Authorization", "Bearer "
                                + signedToken("user-1", "tenant-a")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.account.customer_id").value("customer-1"))
                .andExpect(jsonPath("$.account.display_name").value("Acme"))
                .andExpect(jsonPath("$.account.status").value("ACTIVE"))
                .andExpect(jsonPath("$.account.external_ref").value("crm-42"))
                .andExpect(jsonPath("$.orders[0].order_id").value("tenant-a-order"))
                .andExpect(jsonPath("$.orders[0].product_code").value("pro-monthly"))
                .andExpect(jsonPath("$.orders[0].quantity").value(2))
                .andExpect(jsonPath("$.orders[0].amount.amount_minor").value(1299))
                .andExpect(jsonPath("$.orders[0].amount.currency").value("USD"))
                .andExpect(jsonPath("$.orders[0].status").value("FULFILLED"))
                .andExpect(jsonPath("$.orders[0].ordered_at").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.orders[0].fulfilled_at").value("2026-01-01T00:05:00Z"))
                .andExpect(jsonPath("$.orders[0].subscription_id").value("sub-1"))
                .andExpect(jsonPath("$.current_plan.plan_id").value("plan-1"))
                .andExpect(jsonPath("$.current_plan.code").value("pro"))
                .andExpect(jsonPath("$.current_plan.display_name").value("Pro"))
                .andExpect(jsonPath("$.current_plan.price.amount_minor").value(999))
                .andExpect(jsonPath("$.current_plan.price.currency").value("USD"))
                .andExpect(jsonPath("$.current_plan.billing_interval").value("MONTHLY"))
                .andExpect(jsonPath("$.entitlements[0].code").value("seats"))
                .andExpect(jsonPath("$.entitlements[0].value").value("5"))
                .andExpect(jsonPath("$.current_subscription.subscription_id").value("sub-1"))
                .andExpect(jsonPath("$.current_subscription.plan_id").value("plan-1"))
                .andExpect(jsonPath("$.current_subscription.status").value("ACTIVE"))
                .andExpect(jsonPath("$.current_subscription.started_at")
                        .value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.current_subscription.current_period_start")
                        .value("2026-02-01T00:00:00Z"))
                .andExpect(jsonPath("$.current_subscription.current_period_end")
                        .value("2026-03-01T00:00:00Z"))
                .andExpect(jsonPath("$.invoices[0].invoice_id").value("invoice-1"))
                .andExpect(jsonPath("$.invoices[0].status").value("PAID"))
                .andExpect(jsonPath("$.invoices[0].amount.amount_minor").value(999))
                .andExpect(jsonPath("$.invoices[0].amount.currency").value("USD"))
                .andExpect(jsonPath("$.invoices[0].issued_at").value("2026-02-01T00:00:00Z"))
                .andExpect(jsonPath("$.invoices[0].due_at").value("2026-02-08T00:00:00Z"))
                .andExpect(jsonPath("$.invoices[0].paid_at").value("2026-02-02T00:00:00Z"))
                .andExpect(jsonPath("$.payment_attempts[0].payment_attempt_id")
                        .value("attempt-1"))
                .andExpect(jsonPath("$.payment_attempts[0].invoice_id").value("invoice-1"))
                .andExpect(jsonPath("$.payment_attempts[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.payment_attempts[0].amount.amount_minor").value(999))
                .andExpect(jsonPath("$.payment_attempts[0].attempted_at")
                        .value("2026-02-02T00:00:00Z"))
                .andExpect(jsonPath("$.payment_attempts[0].failure_code").doesNotExist())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).doesNotContain("tenant_id");
        verify(repository).load(new TenantId("tenant-a"), new CustomerId("customer-1"));
    }

    @Test
    void hidesCustomerOutsideActorScopeAsNotFound() throws Exception {
        mvc.perform(get("/api/v1/commerce/customers/customer-2")
                        .header("Authorization", "Bearer "
                                + signedToken("user-1", "tenant-a"))
                        .header("X-Request-Id", "request-foreign"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("resource not found"))
                .andExpect(jsonPath("$.requestId").value("request-foreign"))
                .andExpect(jsonPath("$.retryable").value(false));

        verifyNoInteractions(repository);
    }

    @Test
    void sameCustomerSlugReturnsOnlyAuthenticatedTenantData() throws Exception {
        when(repository.load(new TenantId("tenant-a"), new CustomerId("customer-1")))
                .thenReturn(Optional.of(completeSnapshot("tenant-a", "Tenant A Customer")));
        when(repository.load(new TenantId("tenant-b"), new CustomerId("customer-1")))
                .thenReturn(Optional.of(completeSnapshot("tenant-b", "Tenant B Customer")));

        mvc.perform(get("/api/v1/commerce/customers/customer-1")
                        .header("Authorization", "Bearer "
                                + signedToken("user-1", "tenant-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.display_name").value("Tenant A Customer"))
                .andExpect(jsonPath("$.orders[0].order_id").value("tenant-a-order"));

        mvc.perform(get("/api/v1/commerce/customers/customer-1")
                        .header("Authorization", "Bearer "
                                + signedToken("user-1", "tenant-b")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.display_name").value("Tenant B Customer"))
                .andExpect(jsonPath("$.orders[0].order_id").value("tenant-b-order"));

        verify(repository).load(new TenantId("tenant-a"), new CustomerId("customer-1"));
        verify(repository).load(new TenantId("tenant-b"), new CustomerId("customer-1"));
    }

    @Test
    void rejectsUnauthenticatedRequestWithStableEnvelope() throws Exception {
        mvc.perform(get("/api/v1/commerce/customers/customer-1")
                        .header("X-Request-Id", "request-anonymous"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.message").value("authentication required"))
                .andExpect(jsonPath("$.requestId").value("request-anonymous"))
                .andExpect(jsonPath("$.retryable").value(false));

        verifyNoInteractions(repository);
    }

    private static CommercialSnapshot completeSnapshot(String tenantId, String displayName) {
        return new CommercialSnapshot(
                new CustomerAccount(
                        new CustomerId("customer-1"),
                        displayName,
                        "ACTIVE",
                        "crm-42"),
                List.of(new CommerceOrder(
                        tenantId + "-order",
                        "pro-monthly",
                        2,
                        new Money(1299, "USD"),
                        "FULFILLED",
                        Instant.parse("2026-01-01T00:00:00Z"),
                        Instant.parse("2026-01-01T00:05:00Z"),
                        "sub-1")),
                Optional.of(new PlanSummary(
                        "plan-1",
                        "pro",
                        "Pro",
                        new Money(999, "USD"),
                        "MONTHLY")),
                List.of(new Entitlement("seats", "5")),
                Optional.of(new SubscriptionSummary(
                        "sub-1",
                        "plan-1",
                        "ACTIVE",
                        Instant.parse("2026-01-01T00:00:00Z"),
                        Instant.parse("2026-02-01T00:00:00Z"),
                        Instant.parse("2026-03-01T00:00:00Z"))),
                List.of(new InvoiceSummary(
                        "invoice-1",
                        "PAID",
                        new Money(999, "USD"),
                        Instant.parse("2026-02-01T00:00:00Z"),
                        Instant.parse("2026-02-08T00:00:00Z"),
                        Instant.parse("2026-02-02T00:00:00Z"))),
                List.of(new PaymentAttemptSummary(
                        "attempt-1",
                        "invoice-1",
                        "SUCCEEDED",
                        new Money(999, "USD"),
                        Instant.parse("2026-02-02T00:00:00Z"),
                        null)));
    }

    private static String signedToken(String subject, String tenantId) throws Exception {
        byte[] secret = Base64.getDecoder().decode(TEST_SECRET);
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .jwtID("commerce-request-1")
                .issuer("echomind-local")
                .audience(List.of("echomind-api"))
                .issueTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .subject(subject)
                .claim("tenant_id", tenantId)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret));
        return jwt.serialize();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestActorScopeConfiguration {

        @Bean
        ActorScopeResolver actorScopeResolver() {
            return (userId, tenantId, requestId) -> {
                if (!"user-1".equals(userId.value())
                        || !Set.of("tenant-a", "tenant-b").contains(tenantId.value())) {
                    throw new TenantAccessDeniedException("raw membership diagnostic");
                }
                return new ActorScope(
                        userId,
                        tenantId,
                        Set.of(ActorRole.CUSTOMER),
                        Set.of("customer-1"),
                        requestId);
            };
        }
    }
}
