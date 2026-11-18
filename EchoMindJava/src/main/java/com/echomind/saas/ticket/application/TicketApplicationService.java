package com.echomind.saas.ticket.application;

import com.echomind.saas.api.error.ResourceNotFoundException;
import com.echomind.saas.identity.application.TenantAccessDeniedException;
import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.platform.idempotency.IdempotencyReplay;
import com.echomind.saas.platform.idempotency.IdempotencyRepository;
import com.echomind.saas.platform.outbox.OutboxMessage;
import com.echomind.saas.platform.outbox.OutboxRepository;
import com.echomind.saas.ticket.domain.InvalidTicketTransitionException;
import com.echomind.saas.ticket.domain.Ticket;
import com.echomind.saas.ticket.domain.TicketEvent;
import com.echomind.saas.ticket.domain.TicketId;
import com.echomind.saas.ticket.domain.TicketPriority;
import com.echomind.saas.ticket.domain.TicketStateMachine;
import com.echomind.saas.ticket.domain.TicketStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketApplicationService implements TicketCommands, TicketQueries {

    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final String DEFAULT_QUEUE = "support-default";

    private final TicketRepository repository;
    private final IdempotencyRepository idempotency;
    private final OutboxRepository outbox;
    private final TicketStateMachine stateMachine;
    private final TicketSlaPolicy slaPolicy;
    private final ObjectMapper objectMapper;

    public TicketApplicationService(
            TicketRepository repository,
            IdempotencyRepository idempotency,
            OutboxRepository outbox,
            TicketStateMachine stateMachine,
            ObjectMapper objectMapper) {
        this(
                repository,
                idempotency,
                outbox,
                stateMachine,
                new TicketSlaPolicy(),
                objectMapper);
    }

    @Autowired
    public TicketApplicationService(
            TicketRepository repository,
            IdempotencyRepository idempotency,
            OutboxRepository outbox,
            TicketStateMachine stateMachine,
            TicketSlaPolicy slaPolicy,
            ObjectMapper objectMapper) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
        this.slaPolicy = Objects.requireNonNull(slaPolicy, "slaPolicy");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper")
                .copy()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    @Transactional
    public TicketCreationResult create(CreateTicketCommand command, ActorScope actor) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(actor, "actor");
        requireCreateAccess(actor, command.customerId());
        if (!repository.customerExists(actor.tenantId(), command.customerId())) {
            throw new ResourceNotFoundException();
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String commercialRefsJson = normalizeJson(command.commercialRefsJson());
        String requestHash = requestHash(command, commercialRefsJson, actor);
        Optional<String> attemptToken = idempotency.tryAcquire(
                actor.tenantId(),
                command.idempotencyKey(),
                requestHash,
                now.plus(IDEMPOTENCY_TTL));
        if (attemptToken.isEmpty()) {
            return new TicketCreationResult(
                    completedReplay(actor.tenantId(), command.idempotencyKey()),
                    true);
        }

        String ticketIdValue = "ticket-" + UUID.randomUUID();
        TicketId ticketId = new TicketId(ticketIdValue);
        String queueName = command.queueName() == null
                ? DEFAULT_QUEUE : command.queueName();
        Ticket ticket = new Ticket(
                ticketId,
                command.customerId(),
                actor.userId().value(),
                command.subject(),
                command.description(),
                TicketStatus.QUEUED,
                command.priority(),
                queueName,
                null,
                command.conversationId(),
                null,
                commercialRefsJson,
                slaPolicy.dueAt(command.priority(), now),
                0,
                now,
                now,
                null,
                null);

        Instant secondEventAt = now.plusNanos(1_000);
        List<TicketEvent> initialEvents = List.of(
                event(
                        ticketId,
                        "ticket.opened",
                        null,
                        TicketStatus.OPEN,
                        actor,
                        null,
                        now),
                event(
                        ticketId,
                        "ticket.queued",
                        TicketStatus.OPEN,
                        TicketStatus.QUEUED,
                        actor,
                        null,
                        secondEventAt));
        repository.insert(actor.tenantId(), ticket, initialEvents);
        appendOutbox(ticket, actor, "ticket.created", now);
        idempotency.complete(
                actor.tenantId(),
                command.idempotencyKey(),
                attemptToken.orElseThrow(),
                writeJson(ticket));
        return new TicketCreationResult(ticket, false);
    }

    @Override
    @Transactional
    public Ticket claim(ClaimTicketCommand command, ActorScope actor) {
        Objects.requireNonNull(command, "command");
        requireSupport(actor);
        Ticket current = loadForRead(command.ticketId(), actor);
        return transition(
                current,
                command.expectedVersion(),
                actor,
                "ticket.claimed",
                null,
                () -> stateMachine.requireClaimable(current.status()),
                now -> new Ticket(
                        current.ticketId(),
                        current.customerId(),
                        current.createdByUserId(),
                        current.subject(),
                        current.description(),
                        TicketStatus.CLAIMED,
                        current.priority(),
                        current.queueName(),
                        actor.userId().value(),
                        current.conversationId(),
                        current.resolution(),
                        current.commercialRefsJson(),
                        current.slaDueAt(),
                        current.version() + 1,
                        current.createdAt(),
                        now,
                        null,
                        null));
    }

    @Override
    @Transactional
    public Ticket resolve(ResolveTicketCommand command, ActorScope actor) {
        Objects.requireNonNull(command, "command");
        requireSupport(actor);
        Ticket current = loadForRead(command.ticketId(), actor);
        return transition(
                current,
                command.expectedVersion(),
                actor,
                "ticket.resolved",
                command.resolution(),
                () -> stateMachine.requireResolvable(current.status()),
                now -> new Ticket(
                        current.ticketId(),
                        current.customerId(),
                        current.createdByUserId(),
                        current.subject(),
                        current.description(),
                        TicketStatus.RESOLVED,
                        current.priority(),
                        current.queueName(),
                        current.assigneeUserId(),
                        current.conversationId(),
                        command.resolution(),
                        current.commercialRefsJson(),
                        current.slaDueAt(),
                        current.version() + 1,
                        current.createdAt(),
                        now,
                        now,
                        null));
    }

    @Override
    @Transactional
    public Ticket close(CloseTicketCommand command, ActorScope actor) {
        Objects.requireNonNull(command, "command");
        requireSupport(actor);
        Ticket current = loadForRead(command.ticketId(), actor);
        return transition(
                current,
                command.expectedVersion(),
                actor,
                "ticket.closed",
                null,
                () -> stateMachine.requireClosable(current.status()),
                now -> new Ticket(
                        current.ticketId(),
                        current.customerId(),
                        current.createdByUserId(),
                        current.subject(),
                        current.description(),
                        TicketStatus.CLOSED,
                        current.priority(),
                        current.queueName(),
                        current.assigneeUserId(),
                        current.conversationId(),
                        current.resolution(),
                        current.commercialRefsJson(),
                        current.slaDueAt(),
                        current.version() + 1,
                        current.createdAt(),
                        now,
                        current.resolvedAt(),
                        now));
    }

    @Override
    @Transactional
    public Ticket reopen(ReopenTicketCommand command, ActorScope actor) {
        Objects.requireNonNull(command, "command");
        Ticket current = loadForRead(command.ticketId(), actor);
        return transition(
                current,
                command.expectedVersion(),
                actor,
                "ticket.reopened",
                command.reason(),
                () -> stateMachine.requireReopenable(current.status()),
                now -> new Ticket(
                        current.ticketId(),
                        current.customerId(),
                        current.createdByUserId(),
                        current.subject(),
                        current.description(),
                        TicketStatus.REOPENED,
                        current.priority(),
                        current.queueName() == null ? DEFAULT_QUEUE : current.queueName(),
                        null,
                        current.conversationId(),
                        null,
                        current.commercialRefsJson(),
                        slaPolicy.dueAt(current.priority(), now),
                        current.version() + 1,
                        current.createdAt(),
                        now,
                        null,
                        null));
    }

    @Override
    public Ticket get(String ticketId, ActorScope actor) {
        return loadForRead(ticketId, actor);
    }

    @Override
    public List<Ticket> list(TicketFilter filter, ActorScope actor) {
        Objects.requireNonNull(filter, "filter");
        requireSupport(actor);
        return repository.list(actor.tenantId(), filter);
    }

    @Override
    public List<TicketEvent> events(String ticketId, ActorScope actor) {
        Ticket ticket = loadForRead(ticketId, actor);
        return repository.events(actor.tenantId(), ticket.ticketId());
    }

    private Ticket transition(
            Ticket current,
            long expectedVersion,
            ActorScope actor,
            String eventType,
            String reason,
            Runnable stateCheck,
            java.util.function.Function<Instant, Ticket> nextFactory) {
        if (current.version() != expectedVersion) {
            throw new TicketConflictException();
        }
        try {
            stateCheck.run();
        } catch (InvalidTicketTransitionException exception) {
            throw new TicketConflictException();
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Ticket next = nextFactory.apply(now);
        TicketEvent event = event(
                current.ticketId(),
                eventType,
                current.status(),
                next.status(),
                actor,
                reason,
                now);
        if (!repository.update(actor.tenantId(), current, next, event)) {
            throw new TicketConflictException();
        }
        appendOutbox(next, actor, eventType, now);
        return next;
    }

    private Ticket loadForRead(String ticketIdValue, ActorScope actor) {
        Objects.requireNonNull(actor, "actor");
        TicketId ticketId = new TicketId(ticketIdValue);
        Ticket ticket = repository.find(actor.tenantId(), ticketId)
                .orElseThrow(ResourceNotFoundException::new);
        if (!canRead(actor, ticket)) {
            throw new ResourceNotFoundException();
        }
        return ticket;
    }

    private Ticket completedReplay(TenantId tenantId, String idempotencyKey) {
        IdempotencyReplay replay = idempotency.findCompleted(tenantId, idempotencyKey)
                .orElseThrow(IdempotencyInProgressException::new);
        try {
            return objectMapper.readValue(replay.responseJson(), Ticket.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored idempotency response is invalid", exception);
        }
    }

    private void requireCreateAccess(ActorScope actor, String customerId) {
        if (actor.hasRole(ActorRole.CUSTOMER)
                && !actor.customerIds().contains(customerId)) {
            throw new ResourceNotFoundException();
        }
        if (!actor.hasRole(ActorRole.CUSTOMER)
                && !actor.hasRole(ActorRole.SUPPORT_AGENT)
                && !actor.hasRole(ActorRole.TENANT_ADMIN)) {
            throw new TenantAccessDeniedException("ticket creation is not permitted");
        }
    }

    private static boolean canRead(ActorScope actor, Ticket ticket) {
        if (actor.hasRole(ActorRole.SUPPORT_AGENT)
                || actor.hasRole(ActorRole.TENANT_ADMIN)) {
            return true;
        }
        return actor.hasRole(ActorRole.CUSTOMER)
                && actor.customerIds().contains(ticket.customerId());
    }

    private static void requireSupport(ActorScope actor) {
        Objects.requireNonNull(actor, "actor");
        if (!actor.hasRole(ActorRole.SUPPORT_AGENT)
                && !actor.hasRole(ActorRole.TENANT_ADMIN)) {
            throw new TenantAccessDeniedException("support role is required");
        }
    }

    private void appendOutbox(
            Ticket ticket, ActorScope actor, String eventType, Instant occurredAt) {
        OutboxPayload payload = new OutboxPayload(
                ticket.ticketId().value(),
                ticket.customerId(),
                actor.userId().value(),
                ticket.status().name(),
                ticket.priority().name(),
                ticket.assigneeUserId(),
                ticket.version(),
                occurredAt);
        outbox.append(new OutboxMessage(
                UUID.randomUUID().toString(),
                actor.tenantId(),
                "ticket",
                ticket.ticketId().value(),
                eventType,
                writeJson(payload),
                occurredAt));
    }

    private TicketEvent event(
            TicketId ticketId,
            String eventType,
            TicketStatus fromStatus,
            TicketStatus toStatus,
            ActorScope actor,
            String reason,
            Instant createdAt) {
        return new TicketEvent(
                eventId(createdAt),
                ticketId,
                eventType,
                fromStatus,
                toStatus,
                actor.userId().value(),
                reason,
                writeJson(new EventPayload(toStatus, reason)),
                createdAt);
    }

    private String requestHash(
            CreateTicketCommand command, String commercialRefsJson, ActorScope actor) {
        CreateFingerprint fingerprint = new CreateFingerprint(
                actor.tenantId().value(),
                actor.userId().value(),
                command.customerId(),
                command.subject(),
                command.description(),
                command.priority().name(),
                command.queueName(),
                command.conversationId(),
                commercialRefsJson);
        byte[] serialized = writeBytes(fingerprint);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(serialized));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private byte[] writeBytes(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("request cannot be serialized", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize ticket workflow data", exception);
        }
    }

    private String normalizeJson(String value) {
        if (value == null) {
            return null;
        }
        try {
            JsonNode tree = objectMapper.readTree(value);
            return objectMapper.writeValueAsString(tree);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("commercialRefsJson must contain valid JSON");
        }
    }

    private static String eventId(Instant createdAt) {
        long epochNanos = Math.addExact(
                Math.multiplyExact(createdAt.getEpochSecond(), 1_000_000_000L),
                createdAt.getNano());
        return "evt-%019d-%s".formatted(epochNanos, UUID.randomUUID());
    }

    private record CreateFingerprint(
            String tenantId,
            String userId,
            String customerId,
            String subject,
            String description,
            String priority,
            String queueName,
            String conversationId,
            String commercialRefsJson
    ) {
    }

    private record EventPayload(
            TicketStatus status,
            String reason
    ) {
    }

    private record OutboxPayload(
            String ticketId,
            String customerId,
            String actorUserId,
            String status,
            String priority,
            String assigneeUserId,
            long version,
            Instant occurredAt
    ) {
    }
}
