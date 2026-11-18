package com.echomind.saas.ticket.infrastructure;

import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.testsupport.MySqlTestContainers;
import com.echomind.saas.ticket.application.TicketFilter;
import com.echomind.saas.ticket.domain.Ticket;
import com.echomind.saas.ticket.domain.TicketEvent;
import com.echomind.saas.ticket.domain.TicketId;
import com.echomind.saas.ticket.domain.TicketPriority;
import com.echomind.saas.ticket.domain.TicketStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class JdbcTicketRepositoryTest {

    private static final Instant CREATED_AT = Instant.parse("2026-11-18T03:04:05.123456Z");
    private static final TenantId TENANT = new TenantId("tenant-a");

    @Container
    static final MySQLContainer<?> MYSQL = MySqlTestContainers.create();

    private JdbcTemplate jdbc;
    private JdbcTicketRepository repository;

    @BeforeEach
    void setUp() {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        repository = new JdbcTicketRepository(jdbc, new ObjectMapper());
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
    void customerExistenceIsTenantScoped() {
        assertThat(repository.customerExists(TENANT, "customer-a")).isTrue();
        assertThat(repository.customerExists(new TenantId("tenant-b"), "customer-a")).isFalse();
        assertThat(repository.customerExists(TENANT, "customer-missing")).isFalse();
    }

    @Test
    void insertsLoadsAndListsOnlyTheSelectedTenant() {
        Ticket ticket = ticket("ticket-1", TicketStatus.QUEUED, 0, null);
        repository.insert(TENANT, ticket, List.of(
                event("ticket-1", "event-1", "ticket.opened", null,
                        TicketStatus.OPEN, CREATED_AT),
                event(
                        "ticket-1",
                        "event-2",
                        "ticket.queued",
                        TicketStatus.OPEN,
                        TicketStatus.QUEUED,
                        CREATED_AT.plusNanos(1_000))));

        assertThat(repository.find(TENANT, new TicketId("ticket-1"))).contains(ticket);
        assertThat(repository.find(new TenantId("tenant-b"), new TicketId("ticket-1")))
                .isEmpty();
        assertThat(repository.list(TENANT, new TicketFilter(null, null, null, 20)))
                .extracting(value -> value.ticketId().value())
                .containsExactly("ticket-1");
        assertThat(repository.events(TENANT, new TicketId("ticket-1")))
                .extracting(TicketEvent::eventType)
                .containsExactly("ticket.opened", "ticket.queued");
    }

    @Test
    void listAppliesFiltersAndStableDescendingOrder() {
        repository.insert(TENANT, ticket("ticket-a", TicketStatus.QUEUED, 0, null), List.of(
                event("ticket-a", "event-a", "ticket.opened", null,
                        TicketStatus.OPEN, CREATED_AT)));
        repository.insert(TENANT, ticket("ticket-b", TicketStatus.QUEUED, 0, null), List.of(
                event("ticket-b", "event-b", "ticket.opened", null,
                        TicketStatus.OPEN, CREATED_AT)));
        repository.insert(TENANT, ticket("ticket-c", TicketStatus.CLAIMED, 0, "support-1"), List.of(
                event("ticket-c", "event-c", "ticket.opened", null,
                        TicketStatus.OPEN, CREATED_AT)));

        assertThat(repository.list(TENANT, new TicketFilter(null, null, null, 2)))
                .extracting(value -> value.ticketId().value())
                .containsExactly("ticket-c", "ticket-b");
        assertThat(repository.list(
                TENANT, new TicketFilter(TicketStatus.QUEUED, null, null, 20)))
                .extracting(value -> value.ticketId().value())
                .containsExactly("ticket-b", "ticket-a");
        assertThat(repository.list(
                TENANT, new TicketFilter(null, null, "support-1", 20)))
                .extracting(value -> value.ticketId().value())
                .containsExactly("ticket-c");
    }

    @Test
    void optimisticUpdateAdvancesOnceAndAppendsOneEvent() {
        Ticket current = ticket("ticket-1", TicketStatus.QUEUED, 0, null);
        repository.insert(TENANT, current, List.of(
                event("ticket-1", "event-1", "ticket.queued", TicketStatus.OPEN,
                        TicketStatus.QUEUED, CREATED_AT)));
        Ticket next = ticket("ticket-1", TicketStatus.CLAIMED, 1, "support-1");
        TicketEvent claimed = event(
                "ticket-1",
                "event-2",
                "ticket.claimed",
                TicketStatus.QUEUED,
                TicketStatus.CLAIMED,
                CREATED_AT.plusSeconds(1));

        assertThat(repository.update(TENANT, current, next, claimed)).isTrue();
        assertThat(repository.find(TENANT, new TicketId("ticket-1")))
                .get()
                .extracting(Ticket::status, Ticket::assigneeUserId, Ticket::version)
                .containsExactly(TicketStatus.CLAIMED, "support-1", 1L);
        assertThat(repository.events(TENANT, new TicketId("ticket-1")))
                .extracting(TicketEvent::eventType)
                .containsExactly("ticket.queued", "ticket.claimed");

        Ticket staleNext = ticket("ticket-1", TicketStatus.RESOLVED, 1, "support-1");
        TicketEvent staleResolved = event(
                "ticket-1",
                "event-3",
                "ticket.resolved",
                TicketStatus.CLAIMED,
                TicketStatus.RESOLVED,
                CREATED_AT.plusSeconds(2));
        assertThat(repository.update(TENANT, current, staleNext, staleResolved)).isFalse();
        assertThat(repository.events(TENANT, new TicketId("ticket-1"))).hasSize(2);
    }

    private static Ticket ticket(
            String ticketId,
            TicketStatus status,
            long version,
            String assignee) {
        Instant updatedAt = CREATED_AT.plusSeconds(version);
        return new Ticket(
                new TicketId(ticketId),
                "customer-a",
                "customer-user",
                "Subject " + ticketId,
                "Description",
                status,
                TicketPriority.NORMAL,
                "support-default",
                assignee,
                null,
                null,
                "{\"orderId\":\"order-1\"}",
                CREATED_AT.plusSeconds(28_800),
                version,
                CREATED_AT,
                updatedAt,
                status == TicketStatus.RESOLVED ? updatedAt : null,
                status == TicketStatus.CLOSED ? updatedAt : null);
    }

    private static TicketEvent event(
            String ticketId,
            String eventId,
            String eventType,
            TicketStatus from,
            TicketStatus to,
            Instant createdAt) {
        return new TicketEvent(
                eventId,
                new TicketId(ticketId),
                eventType,
                from,
                to,
                "user-1",
                null,
                "{\"source\":\"test\"}",
                createdAt);
    }
}
