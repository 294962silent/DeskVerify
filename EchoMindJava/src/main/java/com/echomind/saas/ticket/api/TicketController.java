package com.echomind.saas.ticket.api;

import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.ticket.application.ClaimTicketCommand;
import com.echomind.saas.ticket.application.CloseTicketCommand;
import com.echomind.saas.ticket.application.CreateTicketCommand;
import com.echomind.saas.ticket.application.ReopenTicketCommand;
import com.echomind.saas.ticket.application.ResolveTicketCommand;
import com.echomind.saas.ticket.application.TicketCommands;
import com.echomind.saas.ticket.application.TicketCreationResult;
import com.echomind.saas.ticket.application.TicketFilter;
import com.echomind.saas.ticket.application.TicketQueries;
import com.echomind.saas.ticket.domain.Ticket;
import com.echomind.saas.ticket.domain.TicketStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

    private final TicketCommands commands;
    private final TicketQueries queries;
    private final ObjectMapper objectMapper;

    public TicketController(
            TicketCommands commands,
            TicketQueries queries,
            ObjectMapper objectMapper) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @PostMapping
    public ResponseEntity<TicketResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TicketCreateRequest request,
            @AuthenticationPrincipal ActorScope actor) {
        TicketCreationResult result = commands.create(
                new CreateTicketCommand(
                        request.customerId(),
                        request.subject(),
                        request.description(),
                        request.priority(),
                        request.queueName(),
                        request.conversationId(),
                        writeJson(request.commercialRefs()),
                        idempotencyKey),
                actor);
        return ResponseEntity
                .status(result.replayed() ? 200 : 201)
                .location(URI.create("/api/v1/tickets/"
                        + result.ticket().ticketId().value()))
                .body(response(result.ticket()));
    }

    @GetMapping("/{ticketId}")
    public TicketResponse get(
            @PathVariable String ticketId,
            @AuthenticationPrincipal ActorScope actor) {
        return response(queries.get(ticketId, actor));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPPORT_AGENT', 'TENANT_ADMIN')")
    public List<TicketResponse> list(
            @RequestParam(required = false) TicketStatus status,
            @RequestParam(name = "customer_id", required = false) String customerId,
            @RequestParam(name = "assignee_user_id", required = false) String assigneeUserId,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal ActorScope actor) {
        return queries.list(
                        new TicketFilter(status, customerId, assigneeUserId, limit),
                        actor)
                .stream()
                .map(this::response)
                .toList();
    }

    @PostMapping("/{ticketId}/claim")
    @PreAuthorize("hasAnyRole('SUPPORT_AGENT', 'TENANT_ADMIN')")
    public TicketResponse claim(
            @PathVariable String ticketId,
            @Valid @RequestBody TicketActionRequest request,
            @AuthenticationPrincipal ActorScope actor) {
        return response(commands.claim(
                new ClaimTicketCommand(ticketId, request.expectedVersion()),
                actor));
    }

    @PostMapping("/{ticketId}/resolve")
    @PreAuthorize("hasAnyRole('SUPPORT_AGENT', 'TENANT_ADMIN')")
    public TicketResponse resolve(
            @PathVariable String ticketId,
            @Valid @RequestBody TicketActionRequest.Resolve request,
            @AuthenticationPrincipal ActorScope actor) {
        return response(commands.resolve(
                new ResolveTicketCommand(
                        ticketId,
                        request.expectedVersion(),
                        request.resolution()),
                actor));
    }

    @PostMapping("/{ticketId}/close")
    @PreAuthorize("hasAnyRole('SUPPORT_AGENT', 'TENANT_ADMIN')")
    public TicketResponse close(
            @PathVariable String ticketId,
            @Valid @RequestBody TicketActionRequest request,
            @AuthenticationPrincipal ActorScope actor) {
        return response(commands.close(
                new CloseTicketCommand(ticketId, request.expectedVersion()),
                actor));
    }

    @PostMapping("/{ticketId}/reopen")
    public TicketResponse reopen(
            @PathVariable String ticketId,
            @Valid @RequestBody TicketActionRequest.Reopen request,
            @AuthenticationPrincipal ActorScope actor) {
        return response(commands.reopen(
                new ReopenTicketCommand(
                        ticketId,
                        request.expectedVersion(),
                        request.reason()),
                actor));
    }

    @GetMapping("/{ticketId}/events")
    public List<TicketResponse.EventResponse> events(
            @PathVariable String ticketId,
            @AuthenticationPrincipal ActorScope actor) {
        return queries.events(ticketId, actor)
                .stream()
                .map(event -> TicketResponse.EventResponse.from(event, objectMapper))
                .toList();
    }

    private TicketResponse response(Ticket ticket) {
        return TicketResponse.from(ticket, objectMapper);
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("commercial_refs must contain valid JSON");
        }
    }
}
