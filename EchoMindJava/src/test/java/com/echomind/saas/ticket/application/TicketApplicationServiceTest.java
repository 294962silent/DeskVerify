package com.echomind.saas.ticket.application;

import com.echomind.saas.api.error.ResourceNotFoundException;
import com.echomind.saas.identity.application.TenantAccessDeniedException;
import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.UserId;
import com.echomind.saas.platform.idempotency.IdempotencyReplay;
import com.echomind.saas.platform.idempotency.IdempotencyRepository;
import com.echomind.saas.platform.outbox.OutboxMessage;
import com.echomind.saas.platform.outbox.OutboxRepository;
import com.echomind.saas.ticket.domain.Ticket;
import com.echomind.saas.ticket.domain.TicketEvent;
import com.echomind.saas.ticket.domain.TicketId;
import com.echomind.saas.ticket.domain.TicketPriority;
import com.echomind.saas.ticket.domain.TicketStateMachine;
import com.echomind.saas.ticket.domain.TicketStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TicketApplicationServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-11-18T02:03:04Z");

    private final TicketRepository repository = mock(TicketRepository.class);
    private final IdempotencyRepository idempotency = mock(IdempotencyRepository.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final TicketApplicationService service = new TicketApplicationService(
            repository,
            idempotency,
            outbox,
            new TicketStateMachine(),
            objectMapper);

    @Test
    void createsQueuedTicketWithInitialHistoryOutboxAndCompletedIdempotency()
            throws Exception {
        ActorScope actor = customerActor("customer-a");
        CreateTicketCommand command = createCommand("customer-a", "idem-1");
        when(repository.customerExists(new TenantId("tenant-a"), "customer-a")).thenReturn(true);
        when(idempotency.tryAcquire(any(), eq("idem-1"), anyString(), any()))
                .thenReturn(Optional.of("attempt-1"));

        Ticket created = service.create(command, actor);

        assertThat(created.status()).isEqualTo(TicketStatus.QUEUED);
        assertThat(created.version()).isZero();
        assertThat(created.priority()).isEqualTo(TicketPriority.HIGH);
        assertThat(created.queueName()).isEqualTo("priority-support");
        assertThat(created.slaDueAt()).isAfter(created.createdAt());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TicketEvent>> eventsCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository).insert(eq(new TenantId("tenant-a")), eq(created), eventsCaptor.capture());
        assertThat(eventsCaptor.getValue()).extracting(TicketEvent::eventType)
                .containsExactly("ticket.opened", "ticket.queued");
        assertThat(eventsCaptor.getValue()).extracting(TicketEvent::toStatus)
                .containsExactly(TicketStatus.OPEN, TicketStatus.QUEUED);
        assertThat(eventsCaptor.getValue().get(1).createdAt())
                .isAfter(eventsCaptor.getValue().get(0).createdAt());

        ArgumentCaptor<OutboxMessage> outboxCaptor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outbox).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.created");
        assertThat(outboxCaptor.getValue().aggregateId()).isEqualTo(created.ticketId().value());

        ArgumentCaptor<String> responseCaptor = ArgumentCaptor.forClass(String.class);
        verify(idempotency).complete(
                eq(new TenantId("tenant-a")),
                eq("idem-1"),
                eq("attempt-1"),
                responseCaptor.capture());
        assertThat(objectMapper.readTree(responseCaptor.getValue())
                .path("ticketId").path("value").asText())
                .isEqualTo(created.ticketId().value());
    }

    @Test
    void completedReplayReturnsTheOriginalTicketWithoutWritingAgain() {
        ActorScope actor = customerActor("customer-a");
        Ticket existing = ticket(
                "ticket-existing", "customer-a", TicketStatus.QUEUED, 0, null);
        when(repository.customerExists(new TenantId("tenant-a"), "customer-a")).thenReturn(true);
        when(idempotency.tryAcquire(any(), anyString(), anyString(), any()))
                .thenReturn(Optional.empty());
        when(idempotency.findCompleted(new TenantId("tenant-a"), "idem-1"))
                .thenReturn(Optional.of(new IdempotencyReplay(
                        write(existing), Instant.parse("2026-11-18T02:10:00Z"))));

        Ticket replayed = service.create(createCommand("customer-a", "idem-1"), actor);

        assertThat(replayed).isEqualTo(existing);
        verify(repository, never()).insert(any(), any(), any());
        verify(outbox, never()).append(any());
        verify(idempotency, never()).complete(any(), anyString(), anyString(), anyString());
    }

    @Test
    void activeDuplicateFailsWithoutWriting() {
        when(repository.customerExists(new TenantId("tenant-a"), "customer-a")).thenReturn(true);
        when(idempotency.tryAcquire(any(), anyString(), anyString(), any()))
                .thenReturn(Optional.empty());
        when(idempotency.findCompleted(any(), anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(
                createCommand("customer-a", "idem-active"),
                customerActor("customer-a")))
                .isInstanceOf(IdempotencyInProgressException.class);

        verify(repository, never()).insert(any(), any(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void customerCannotCreateTicketForAnotherCustomer() {
        assertThatThrownBy(() -> service.create(
                createCommand("customer-b", "idem-1"),
                customerActor("customer-a")))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(repository, idempotency, outbox);
    }

    @Test
    void missingCustomerMappingIsNotFoundBeforeIdempotencyAcquisition() {
        when(repository.customerExists(new TenantId("tenant-a"), "customer-a")).thenReturn(false);

        assertThatThrownBy(() -> service.create(
                createCommand("customer-a", "idem-1"),
                customerActor("customer-a")))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(idempotency, outbox);
    }

    @Test
    void supportAgentCanCreateForAnyTenantCustomer() {
        ActorScope actor = actor("support-1", Set.of(ActorRole.SUPPORT_AGENT), Set.of());
        when(repository.customerExists(new TenantId("tenant-a"), "customer-a")).thenReturn(true);
        when(idempotency.tryAcquire(any(), anyString(), anyString(), any()))
                .thenReturn(Optional.of("attempt-1"));

        Ticket created = service.create(createCommand("customer-a", "idem-support"), actor);

        assertThat(created.createdByUserId()).isEqualTo("support-1");
        verify(repository).insert(eq(new TenantId("tenant-a")), eq(created), any());
    }

    @Test
    void staleVersionWinsAsOneStableConflict() {
        Ticket queued = ticket("ticket-1", "customer-a", TicketStatus.QUEUED, 3, null);
        when(repository.find(new TenantId("tenant-a"), new TicketId("ticket-1")))
                .thenReturn(Optional.of(queued));

        assertThatThrownBy(() -> service.claim(
                new ClaimTicketCommand("ticket-1", 2), supportActor()))
                .isInstanceOf(TicketConflictException.class)
                .hasMessage("ticket state conflict");

        verify(repository, never()).update(any(), any(), any(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void claimAssignsTheActorAndWritesOneImmutableEventAndOutboxMessage() {
        Ticket queued = ticket("ticket-1", "customer-a", TicketStatus.QUEUED, 2, null);
        when(repository.find(new TenantId("tenant-a"), new TicketId("ticket-1")))
                .thenReturn(Optional.of(queued));
        when(repository.update(any(), any(), any(), any())).thenReturn(true);

        Ticket claimed = service.claim(
                new ClaimTicketCommand("ticket-1", 2), supportActor());

        assertThat(claimed.status()).isEqualTo(TicketStatus.CLAIMED);
        assertThat(claimed.assigneeUserId()).isEqualTo("support-1");
        assertThat(claimed.version()).isEqualTo(3);

        ArgumentCaptor<TicketEvent> eventCaptor = ArgumentCaptor.forClass(TicketEvent.class);
        verify(repository).update(
                eq(new TenantId("tenant-a")),
                eq(queued),
                eq(claimed),
                eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType()).isEqualTo("ticket.claimed");
        assertThat(eventCaptor.getValue().fromStatus()).isEqualTo(TicketStatus.QUEUED);
        assertThat(eventCaptor.getValue().toStatus()).isEqualTo(TicketStatus.CLAIMED);
        verify(outbox).append(any(OutboxMessage.class));
    }

    @Test
    void invalidSourceStateBecomesConflictWithoutOptimisticWrite() {
        Ticket resolved = ticket("ticket-1", "customer-a", TicketStatus.RESOLVED, 4, "support-1");
        when(repository.find(new TenantId("tenant-a"), new TicketId("ticket-1")))
                .thenReturn(Optional.of(resolved));

        assertThatThrownBy(() -> service.claim(
                new ClaimTicketCommand("ticket-1", 4), supportActor()))
                .isInstanceOf(TicketConflictException.class);

        verify(repository, never()).update(any(), any(), any(), any());
    }

    @Test
    void resolveCloseAndReopenPreserveHistoryAndClearQueueMetadataOnReopen() {
        AtomicReference<Ticket> current = new AtomicReference<>(
                ticket("ticket-1", "customer-a", TicketStatus.CLAIMED, 3, "support-1"));
        when(repository.find(new TenantId("tenant-a"), new TicketId("ticket-1")))
                .thenAnswer(invocation -> Optional.of(current.get()));
        when(repository.update(any(), any(), any(), any())).thenAnswer(invocation -> {
            Ticket next = invocation.getArgument(2);
            current.set(next);
            return true;
        });

        Ticket resolved = service.resolve(
                new ResolveTicketCommand("ticket-1", 3, "Reset completed"),
                supportActor());
        Ticket closed = service.close(
                new CloseTicketCommand("ticket-1", resolved.version()),
                supportActor());
        Ticket reopened = service.reopen(
                new ReopenTicketCommand("ticket-1", closed.version(), "Issue returned"),
                customerActor("customer-a"));

        assertThat(resolved.status()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(resolved.resolution()).isEqualTo("Reset completed");
        assertThat(resolved.resolvedAt()).isNotNull();
        assertThat(closed.status()).isEqualTo(TicketStatus.CLOSED);
        assertThat(closed.closedAt()).isNotNull();
        assertThat(reopened.status()).isEqualTo(TicketStatus.REOPENED);
        assertThat(reopened.assigneeUserId()).isNull();
        assertThat(reopened.resolution()).isNull();
        assertThat(reopened.resolvedAt()).isNull();
        assertThat(reopened.closedAt()).isNull();

        ArgumentCaptor<TicketEvent> events = ArgumentCaptor.forClass(TicketEvent.class);
        verify(repository, times(3)).update(any(), any(), any(), events.capture());
        assertThat(events.getAllValues()).extracting(TicketEvent::eventType)
                .containsExactly("ticket.resolved", "ticket.closed", "ticket.reopened");
        assertThat(events.getAllValues().getLast().reason()).isEqualTo("Issue returned");
        verify(outbox, times(3)).append(any(OutboxMessage.class));
    }

    @Test
    void customerCannotListTenantTickets() {
        assertThatThrownBy(() -> service.list(
                new TicketFilter(null, null, null, 20),
                customerActor("customer-a")))
                .isInstanceOf(TenantAccessDeniedException.class);
    }

    @Test
    void foreignTicketIsIndistinguishableFromMissing() {
        Ticket foreign = ticket("ticket-1", "customer-b", TicketStatus.QUEUED, 0, null);
        when(repository.find(new TenantId("tenant-a"), new TicketId("ticket-1")))
                .thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.get("ticket-1", customerActor("customer-a")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private String write(Ticket ticket) {
        try {
            return objectMapper.writeValueAsString(ticket);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static CreateTicketCommand createCommand(String customerId, String idempotencyKey) {
        return new CreateTicketCommand(
                customerId,
                "Unable to access reports",
                "The reports page has returned an error since this morning.",
                TicketPriority.HIGH,
                "priority-support",
                "conversation-1",
                "{\"subscriptionId\":\"sub-1\"}",
                idempotencyKey);
    }

    private static Ticket ticket(
            String ticketId,
            String customerId,
            TicketStatus status,
            long version,
            String assignee) {
        return new Ticket(
                new TicketId(ticketId),
                customerId,
                "customer-user",
                "Subject",
                "Description",
                status,
                TicketPriority.NORMAL,
                "support-default",
                assignee,
                null,
                status == TicketStatus.RESOLVED ? "Resolved" : null,
                null,
                CREATED_AT.plusSeconds(3600),
                version,
                CREATED_AT,
                CREATED_AT.plusSeconds(version),
                status == TicketStatus.RESOLVED || status == TicketStatus.CLOSED
                        ? CREATED_AT.plusSeconds(version) : null,
                status == TicketStatus.CLOSED ? CREATED_AT.plusSeconds(version) : null);
    }

    private static ActorScope customerActor(String customerId) {
        return actor("customer-user", Set.of(ActorRole.CUSTOMER), Set.of(customerId));
    }

    private static ActorScope supportActor() {
        return actor("support-1", Set.of(ActorRole.SUPPORT_AGENT), Set.of());
    }

    private static ActorScope actor(
            String userId, Set<ActorRole> roles, Set<String> customerIds) {
        return new ActorScope(
                new UserId(userId),
                new TenantId("tenant-a"),
                roles,
                customerIds,
                "request-1");
    }
}
