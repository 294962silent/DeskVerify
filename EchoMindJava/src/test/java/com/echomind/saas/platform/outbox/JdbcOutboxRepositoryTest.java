package com.echomind.saas.platform.outbox;

import com.echomind.saas.identity.domain.TenantId;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class JdbcOutboxRepositoryTest {

    private static final Instant AVAILABLE_AT = Instant.parse("2025-01-01T00:00:00.123456Z");

    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4").withDatabaseName("echomind");

    private JdbcOutboxRepository repository;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load()
                .migrate();
        jdbc = new JdbcTemplate(
                new DriverManagerDataSource(
                        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.update("delete from outbox_event");
        repository = new JdbcOutboxRepository(jdbc);
    }

    @Test
    void persistsAMessageAndClaimsEveryField() {
        OutboxMessage message = message("00000000-0000-0000-0000-000000000001", "ticket.created");

        repository.append(message);

        assertThat(repository.claimPending(
                        10, "lease-owner", Instant.now().plusSeconds(3600)))
                .containsExactly(message);
    }

    @Test
    void claimsMessagesInStableCreatedAtThenIdOrder() {
        OutboxMessage laterId = message(
                "00000000-0000-0000-0000-000000000002", "ticket.second");
        OutboxMessage earlierId = message(
                "00000000-0000-0000-0000-000000000001", "ticket.first");
        repository.append(laterId);
        repository.append(earlierId);
        Timestamp createdAt = Timestamp.from(Instant.parse("2025-02-01T00:00:00Z"));
        jdbc.update("update outbox_event set created_at = ?", createdAt);

        assertThat(repository.claimPending(
                        10, "lease-owner", Instant.now().plusSeconds(3600)))
                .extracting(OutboxMessage::id)
                .containsExactly(earlierId.id(), laterId.id());
    }

    @Test
    void doesNotReturnAClaimedMessageToAnotherOwnerBeforeExpiry() {
        OutboxMessage message = message(
                "00000000-0000-0000-0000-000000000001", "ticket.created");
        repository.append(message);

        assertThat(repository.claimPending(
                        10, "owner-a", Instant.now().plusSeconds(3600)))
                .containsExactly(message);
        assertThat(repository.claimPending(
                        10, "owner-b", Instant.now().plusSeconds(3600)))
                .isEmpty();
    }

    @Test
    void reclaimsAnExpiredLease() {
        OutboxMessage message = message(
                "00000000-0000-0000-0000-000000000001", "ticket.created");
        repository.append(message);

        assertThat(repository.claimPending(
                        10, "owner-a", Instant.now().minusSeconds(1)))
                .containsExactly(message);
        assertThat(repository.claimPending(
                        10, "owner-b", Instant.now().plusSeconds(3600)))
                .containsExactly(message);
        assertThat(jdbc.queryForObject("""
                select attempts from outbox_event where id = ?
                """, Integer.class, message.id())).isEqualTo(2);
    }

    @Test
    void markPublishedRequiresTheCurrentOwnerAndPublishesTheMessage() {
        OutboxMessage message = message(
                "00000000-0000-0000-0000-000000000001", "ticket.created");
        repository.append(message);
        repository.claimPending(10, "owner-a", Instant.now().plusSeconds(3600));

        assertThatThrownBy(() -> repository.markPublished(message.id(), "owner-b"))
                .isInstanceOf(OutboxStateException.class);
        repository.markPublished(message.id(), "owner-a");

        assertThat(jdbc.queryForObject("""
                select state from outbox_event where id = ?
                """, String.class, message.id())).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("""
                select lease_owner from outbox_event where id = ?
                """, String.class, message.id())).isNull();
    }

    @Test
    void releaseForRetryRequiresTheCurrentOwnerAndSchedulesARetry() {
        OutboxMessage message = message(
                "00000000-0000-0000-0000-000000000001", "ticket.created");
        repository.append(message);
        repository.claimPending(10, "owner-a", Instant.now().plusSeconds(3600));
        Instant retryAt = Instant.now().plusSeconds(300).truncatedTo(ChronoUnit.MICROS);

        assertThatThrownBy(
                () -> repository.releaseForRetry(message.id(), "owner-b", retryAt, "boom"))
                .isInstanceOf(OutboxStateException.class);
        repository.releaseForRetry(message.id(), "owner-a", retryAt, "boom");

        assertThat(jdbc.queryForObject("""
                select state from outbox_event where id = ?
                """, String.class, message.id())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("""
                select last_error from outbox_event where id = ?
                """, String.class, message.id())).isEqualTo("boom");
        assertThat(jdbc.queryForObject("""
                select lease_owner from outbox_event where id = ?
                """, String.class, message.id())).isNull();
        assertThat(jdbc.queryForObject("""
                select lease_until from outbox_event where id = ?
                """, Timestamp.class, message.id())).isNull();
        assertThat(jdbc.queryForObject("""
                select available_at from outbox_event where id = ?
                """, Timestamp.class, message.id()).toInstant()).isEqualTo(retryAt);
    }

    private static OutboxMessage message(String id, String eventType) {
        return new OutboxMessage(
                id,
                new TenantId("tenant-a"),
                "ticket",
                "ticket-1",
                eventType,
                "{\"ticketId\":\"ticket-1\"}",
                AVAILABLE_AT);
    }
}
