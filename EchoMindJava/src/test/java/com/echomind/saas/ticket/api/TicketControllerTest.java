package com.echomind.saas.ticket.api;

import com.echomind.config.AppConfig;
import com.echomind.saas.api.error.ApiAccessDeniedHandler;
import com.echomind.saas.api.error.ApiAuthenticationEntryPoint;
import com.echomind.saas.api.error.ResourceNotFoundException;
import com.echomind.saas.identity.application.ActorScopeResolver;
import com.echomind.saas.identity.application.TenantAccessDeniedException;
import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.UserId;
import com.echomind.saas.identity.security.SaasSecurityConfiguration;
import com.echomind.saas.ticket.application.ClaimTicketCommand;
import com.echomind.saas.ticket.application.CreateTicketCommand;
import com.echomind.saas.ticket.application.IdempotencyInProgressException;
import com.echomind.saas.ticket.application.TicketCommands;
import com.echomind.saas.ticket.application.TicketConflictException;
import com.echomind.saas.ticket.application.TicketCreationResult;
import com.echomind.saas.ticket.application.TicketFilter;
import com.echomind.saas.ticket.application.TicketQueries;
import com.echomind.saas.ticket.domain.Ticket;
import com.echomind.saas.ticket.domain.TicketEvent;
import com.echomind.saas.ticket.domain.TicketId;
import com.echomind.saas.ticket.domain.TicketPriority;
import com.echomind.saas.ticket.domain.TicketStatus;
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
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TicketController.class)
@Import({
        AppConfig.class,
        SaasSecurityConfiguration.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        TicketControllerTest.TestActorScopeConfiguration.class
})
@TestPropertySource(properties =
        "echomind.security.jwt.hmac-secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class TicketControllerTest {

    private static final String TEST_SECRET =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private TicketCommands commands;

    @MockitoBean
    private TicketQueries queries;

    @Test
    void customerCreatesOwnTicketWithStableContract() throws Exception {
        Ticket ticket = ticket("ticket-1", TicketStatus.QUEUED, 0, "customer-1", null);
        when(commands.create(any(), any())).thenReturn(new TicketCreationResult(ticket, false));

        String body = mvc.perform(post("/api/v1/tickets")
                        .header("Authorization", "Bearer " + token("customer-1", "tenant-a"))
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customer_id": "customer-1",
                                  "subject": "Cannot access reports",
                                  "description": "The reports page fails.",
                                  "priority": "HIGH",
                                  "queue_name": "priority-support",
                                  "conversation_id": "conversation-1",
                                  "commercial_refs": {
                                    "subscription_id": "subscription-1"
                                  },
                                  "tenant_id": "tenant-b",
                                  "user_id": "forged-user"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/tickets/ticket-1"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.ticket_id").value("ticket-1"))
                .andExpect(jsonPath("$.customer_id").value("customer-1"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.priority").value("NORMAL"))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).doesNotContain("tenant_id");
        ArgumentCaptor<CreateTicketCommand> command =
                ArgumentCaptor.forClass(CreateTicketCommand.class);
        ArgumentCaptor<ActorScope> actor = ArgumentCaptor.forClass(ActorScope.class);
        verify(commands).create(command.capture(), actor.capture());
        assertThat(actor.getValue().tenantId()).isEqualTo(new TenantId("tenant-a"));
        assertThat(actor.getValue().userId()).isEqualTo(new UserId("customer-1"));
        assertThat(command.getValue().customerId()).isEqualTo("customer-1");
        assertThat(command.getValue().priority()).isEqualTo(TicketPriority.HIGH);
        assertThat(command.getValue().idempotencyKey()).isEqualTo("idem-1");
        assertThat(command.getValue().commercialRefsJson())
                .isEqualTo("{\"subscription_id\":\"subscription-1\"}");
    }

    @Test
    void idempotentReplayReturnsOkAndTheOriginalTicket() throws Exception {
        when(commands.create(any(), any())).thenReturn(
                new TicketCreationResult(
                        ticket("ticket-existing", TicketStatus.QUEUED, 0, "customer-1", null),
                        true));

        mvc.perform(post("/api/v1/tickets")
                        .header("Authorization", "Bearer " + token("customer-1", "tenant-a"))
                        .header("Idempotency-Key", "idem-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("customer-1")))
                .andExpect(status().isOk())
                .andExpect(header().string("Location", "/api/v1/tickets/ticket-existing"))
                .andExpect(jsonPath("$.ticket_id").value("ticket-existing"));
    }

    @Test
    void missingOrMalformedIdempotencyKeyReturnsValidationEnvelope() throws Exception {
        mvc.perform(post("/api/v1/tickets")
                        .header("Authorization", "Bearer " + token("customer-1", "tenant-a"))
                        .header("X-Request-Id", "request-missing-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("customer-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("request validation failed"))
                .andExpect(jsonPath("$.requestId").value("request-missing-key"))
                .andExpect(jsonPath("$.retryable").value(false));

        mvc.perform(post("/api/v1/tickets")
                        .header("Authorization", "Bearer " + token("customer-1", "tenant-a"))
                        .header("Idempotency-Key", "bad key!")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("customer-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(commands);
    }

    @Test
    void foreignTicketIsReturnedAsNotFound() throws Exception {
        when(queries.get("ticket-foreign", new ActorScope(
                        new UserId("customer-1"),
                        new TenantId("tenant-a"),
                        Set.of(ActorRole.CUSTOMER),
                        Set.of("customer-1"),
                        "ticket-request")))
                .thenThrow(new ResourceNotFoundException());

        mvc.perform(get("/api/v1/tickets/ticket-foreign")
                        .header("Authorization", "Bearer " + token("customer-1", "tenant-a"))
                        .header("X-Request-Id", "request-foreign-ticket"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").value("request-foreign-ticket"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void supportAgentClaimsWithExpectedVersion() throws Exception {
        when(commands.claim(any(), any())).thenReturn(
                ticket("ticket-1", TicketStatus.CLAIMED, 1, "customer-1", "support-1"));

        mvc.perform(post("/api/v1/tickets/ticket-1/claim")
                        .header("Authorization", "Bearer " + token("support-1", "tenant-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLAIMED"))
                .andExpect(jsonPath("$.assignee_user_id").value("support-1"))
                .andExpect(jsonPath("$.version").value(1));

        ArgumentCaptor<ClaimTicketCommand> command =
                ArgumentCaptor.forClass(ClaimTicketCommand.class);
        verify(commands).claim(command.capture(), any());
        assertThat(command.getValue().ticketId()).isEqualTo("ticket-1");
        assertThat(command.getValue().expectedVersion()).isZero();
    }

    @Test
    void staleClaimAndActiveDuplicateUseDistinctConflictCodes() throws Exception {
        when(commands.claim(any(), any())).thenThrow(new TicketConflictException());

        mvc.perform(post("/api/v1/tickets/ticket-1/claim")
                        .header("Authorization", "Bearer " + token("support-1", "tenant-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_STATE_CONFLICT"))
                .andExpect(jsonPath("$.message").value("ticket state conflict"))
                .andExpect(jsonPath("$.retryable").value(false));

        when(commands.create(any(), any())).thenThrow(new IdempotencyInProgressException());
        mvc.perform(post("/api/v1/tickets")
                        .header("Authorization", "Bearer " + token("customer-1", "tenant-a"))
                        .header("Idempotency-Key", "idem-in-progress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("customer-1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_IN_PROGRESS"))
                .andExpect(jsonPath("$.message").value("request is still in progress"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void customerCannotListTenantTicketsButSupportCan() throws Exception {
        mvc.perform(get("/api/v1/tickets")
                        .header("Authorization", "Bearer " + token("customer-1", "tenant-a")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        verifyNoInteractions(queries);

        when(queries.list(any(), any())).thenReturn(List.of(
                ticket("ticket-1", TicketStatus.QUEUED, 0, "customer-1", null)));
        mvc.perform(get("/api/v1/tickets")
                        .queryParam("status", "QUEUED")
                        .queryParam("customer_id", "customer-1")
                        .queryParam("limit", "10")
                        .header("Authorization", "Bearer " + token("support-1", "tenant-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ticket_id").value("ticket-1"));

        ArgumentCaptor<TicketFilter> filter = ArgumentCaptor.forClass(TicketFilter.class);
        verify(queries).list(filter.capture(), any());
        assertThat(filter.getValue().status()).isEqualTo(TicketStatus.QUEUED);
        assertThat(filter.getValue().customerId()).isEqualTo("customer-1");
        assertThat(filter.getValue().limit()).isEqualTo(10);
    }

    @Test
    void eventsAreMappedInChronologicalOrder() throws Exception {
        when(queries.events(any(), any())).thenReturn(List.of(
                event("event-1", "ticket.opened", null, TicketStatus.OPEN,
                        "2026-11-18T01:00:00Z"),
                event("event-2", "ticket.queued", TicketStatus.OPEN, TicketStatus.QUEUED,
                        "2026-11-18T01:00:00.000001Z")));

        mvc.perform(get("/api/v1/tickets/ticket-1/events")
                        .header("Authorization", "Bearer " + token("support-1", "tenant-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].event_type").value("ticket.opened"))
                .andExpect(jsonPath("$[1].event_type").value("ticket.queued"))
                .andExpect(jsonPath("$[1].from_status").value("OPEN"))
                .andExpect(jsonPath("$[1].to_status").value("QUEUED"));
    }

    private static String createBody(String customerId) {
        return """
                {
                  "customer_id": "%s",
                  "subject": "Cannot access reports",
                  "description": "The reports page fails.",
                  "priority": "NORMAL"
                }
                """.formatted(customerId);
    }

    private static Ticket ticket(
            String ticketId,
            TicketStatus status,
            long version,
            String customerId,
            String assignee) {
        Instant created = Instant.parse("2026-11-18T01:00:00Z");
        return new Ticket(
                new TicketId(ticketId),
                customerId,
                "customer-1",
                "Cannot access reports",
                "The reports page fails.",
                status,
                TicketPriority.NORMAL,
                "support-default",
                assignee,
                "conversation-1",
                null,
                "{\"subscription_id\":\"subscription-1\"}",
                created.plusSeconds(28_800),
                version,
                created,
                created.plusSeconds(version),
                null,
                null);
    }

    private static TicketEvent event(
            String eventId,
            String eventType,
            TicketStatus from,
            TicketStatus to,
            String createdAt) {
        return new TicketEvent(
                eventId,
                new TicketId("ticket-1"),
                eventType,
                from,
                to,
                "customer-1",
                null,
                "{\"status\":\"" + to + "\"}",
                Instant.parse(createdAt));
    }

    private static String token(String subject, String tenantId) throws Exception {
        byte[] secret = Base64.getDecoder().decode(TEST_SECRET);
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .jwtID("ticket-request")
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
                if (!Set.of("tenant-a", "tenant-b").contains(tenantId.value())) {
                    throw new TenantAccessDeniedException("raw membership diagnostic");
                }
                return switch (userId.value()) {
                    case "support-1" -> new ActorScope(
                            userId,
                            tenantId,
                            Set.of(ActorRole.SUPPORT_AGENT),
                            Set.of(),
                            requestId);
                    case "admin-1" -> new ActorScope(
                            userId,
                            tenantId,
                            Set.of(ActorRole.TENANT_ADMIN),
                            Set.of(),
                            requestId);
                    case "customer-1" -> new ActorScope(
                            userId,
                            tenantId,
                            Set.of(ActorRole.CUSTOMER),
                            Set.of("customer-1"),
                            requestId);
                    default -> throw new TenantAccessDeniedException(
                            "raw membership diagnostic");
                };
            };
        }
    }
}
