package com.echomind.saas.ticket.application;

import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.UserId;
import com.echomind.saas.platform.idempotency.IdempotencyConflictException;
import com.echomind.saas.platform.idempotency.JdbcIdempotencyRepository;
import com.echomind.saas.platform.outbox.JdbcOutboxRepository;
import com.echomind.saas.platform.outbox.OutboxMessage;
import com.echomind.saas.platform.outbox.OutboxRepository;
import com.echomind.saas.testsupport.MySqlTestContainers;
import com.echomind.saas.ticket.domain.Ticket;
import com.echomind.saas.ticket.domain.TicketEvent;
import com.echomind.saas.ticket.domain.TicketPriority;
import com.echomind.saas.ticket.domain.TicketStateMachine;
import com.echomind.saas.ticket.domain.TicketStatus;
import com.echomind.saas.ticket.infrastructure.JdbcTicketRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class TicketApplicationServiceMySqlTest {

    private static final TenantId TENANT = new TenantId("tenant-a");

    @Container
    static final MySQLContainer<?> MYSQL = MySqlTestContainers.create();

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private TicketApplicationService service;

    @BeforeEach
    void setUp() {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        JdbcTicketRepository repository = new JdbcTicketRepository(jdbc, objectMapper);
        service = new TicketApplicationService(
                repository,
                new JdbcIdempotencyRepository(jdbc),
                new JdbcOutboxRepository(jdbc),
                new TicketStateMachine(),
                objectMapper);
        jdbc.update("""
                insert into tenant(id, display_name, status)
                values ('tenant-a', 'Tenant A', 'ACTIVE')
                """);
        jdbc.update("""
                insert into customer_account(
                    tenant_id, customer_id, display_name, status
                ) values ('tenant-a', 'customer-a', 'Customer A', 'ACTIVE')
                """);
    }

    @Test
    void creationCommitsTicketHistoryOutboxAndIdempotencyTogether() {
        Ticket created = inTransaction(() ->
                service.create(command("idem-create", "Unable to access reports"), customerActor()));

        assertThat(created.status()).isEqualTo(TicketStatus.QUEUED);
        assertThat(count("ticket")).isEqualTo(1);
        assertThat(count("ticket_event")).isEqualTo(2);
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select state from idempotency_record
                where tenant_id = 'tenant-a' and idempotency_key = 'idem-create'
                """, String.class)).isEqualTo("COMPLETED");

        Ticket replay = inTransaction(() ->
                service.create(command("idem-create", "Unable to access reports"), customerActor()));

        assertThat(replay).isEqualTo(created);
        assertThat(count("ticket")).isEqualTo(1);
        assertThat(count("ticket_event")).isEqualTo(2);
        assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test
    void reusingAnIdempotencyKeyWithDifferentContentIsRejected() {
        inTransaction(() ->
                service.create(command("idem-conflict", "First subject"), customerActor()));

        assertThatThrownBy(() -> inTransaction(() ->
                service.create(command("idem-conflict", "Different subject"), customerActor())))
                .isInstanceOf(IdempotencyConflictException.class);

        assertThat(count("ticket")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test
    void fullWorkflowWritesOneImmutableEventAndOneOutboxMessagePerTransition() {
        Ticket queued = inTransaction(() ->
                service.create(command("idem-flow", "Unable to access reports"), customerActor()));
        Ticket claimed = inTransaction(() ->
                service.claim(new ClaimTicketCommand(
                        queued.ticketId().value(), queued.version()), supportActor()));
        Ticket resolved = inTransaction(() ->
                service.resolve(new ResolveTicketCommand(
                        claimed.ticketId().value(),
                        claimed.version(),
                        "Entitlement repaired"), supportActor()));
        Ticket closed = inTransaction(() ->
                service.close(new CloseTicketCommand(
                        resolved.ticketId().value(), resolved.version()), supportActor()));
        Ticket reopened = inTransaction(() ->
                service.reopen(new ReopenTicketCommand(
                        closed.ticketId().value(),
                        closed.version(),
                        "The issue returned"), customerActor()));

        assertThat(queued.status()).isEqualTo(TicketStatus.QUEUED);
        assertThat(claimed.status()).isEqualTo(TicketStatus.CLAIMED);
        assertThat(resolved.status()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(closed.status()).isEqualTo(TicketStatus.CLOSED);
        assertThat(reopened.status()).isEqualTo(TicketStatus.REOPENED);
        assertThat(reopened.assigneeUserId()).isNull();
        assertThat(reopened.resolution()).isNull();

        assertThat(inTransaction(() ->
                service.events(queued.ticketId().value(), supportActor())))
                .extracting(TicketEvent::eventType)
                .containsExactly(
                        "ticket.opened",
                        "ticket.queued",
                        "ticket.claimed",
                        "ticket.resolved",
                        "ticket.closed",
                        "ticket.reopened");
        assertThat(jdbc.queryForList(
                "select event_type from outbox_event order by created_at, id", String.class))
                .containsExactly(
                        "ticket.created",
                        "ticket.claimed",
                        "ticket.resolved",
                        "ticket.closed",
                        "ticket.reopened");
    }

    @Test
    void outboxFailureRollsBackBusinessAndIdempotencyWrites() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        OutboxRepository failingOutbox = new JdbcOutboxRepository(jdbc) {
            @Override
            public void append(OutboxMessage message) {
                throw new IllegalStateException("outbox unavailable");
            }
        };
        TicketApplicationService failingService = new TicketApplicationService(
                new JdbcTicketRepository(jdbc, objectMapper),
                new JdbcIdempotencyRepository(jdbc),
                failingOutbox,
                new TicketStateMachine(),
                objectMapper);

        assertThatThrownBy(() -> inTransaction(() ->
                failingService.create(command("idem-rollback", "Will roll back"), customerActor())))
                .hasMessage("outbox unavailable");

        assertThat(count("ticket")).isZero();
        assertThat(count("ticket_event")).isZero();
        assertThat(count("outbox_event")).isZero();
        assertThat(count("idempotency_record")).isZero();
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transactions.execute(status -> action.get());
    }

    private int count(String table) {
        Integer count = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private static CreateTicketCommand command(String key, String subject) {
        return new CreateTicketCommand(
                "customer-a",
                subject,
                "The reports page has failed since this morning.",
                TicketPriority.NORMAL,
                "support-default",
                "conversation-1",
                "{\"subscriptionId\":\"sub-1\"}",
                key);
    }

    private static ActorScope customerActor() {
        return new ActorScope(
                new UserId("customer-user"),
                TENANT,
                Set.of(ActorRole.CUSTOMER),
                Set.of("customer-a"),
                "request-customer");
    }

    private static ActorScope supportActor() {
        return new ActorScope(
                new UserId("support-1"),
                TENANT,
                Set.of(ActorRole.SUPPORT_AGENT),
                Set.of(),
                "request-support");
    }
}
