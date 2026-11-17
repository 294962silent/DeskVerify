package com.echomind.saas.platform.outbox;

import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.testsupport.MySqlTestContainers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
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
class JdbcOutboxRepositoryTest {

    private static final Instant AVAILABLE_AT = Instant.parse("2025-01-01T00:00:00.123456Z");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Container
    static final MySQLContainer<?> MYSQL = MySqlTestContainers.create();

    private JdbcOutboxRepository repository;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.update("delete from outbox_event");
        jdbc.update("delete from tenant where id like 'tx-%'");
        repository = new JdbcOutboxRepository(jdbc);
    }

    @Test
    void persistsAMessageAndClaimsEveryFieldWithANonblankToken() {
        OutboxMessage message = message("00000000-0000-0000-0000-000000000001", "ticket.created");

        appendInTransaction(message);

        assertClaimsContainExactly(
                repository.claimPending(10, "lease-owner", Instant.now().plusSeconds(3600)),
                message);
    }

    @Test
    void doesNotClaimAnActiveLease() {
        OutboxMessage message = message(
                "00000000-0000-0000-0000-000000000001", "ticket.created");
        appendInTransaction(message);

        assertClaimsContainExactly(
                repository.claimPending(10, "owner-a", Instant.now().plusSeconds(3600)),
                message);
        assertThat(repository.claimPending(
                        10, "owner-b", Instant.now().plusSeconds(3600)))
                .isEmpty();
    }

    @Test
    void concurrentClaimsProduceExactlyOneSuccessfulClaim() throws Exception {
        OutboxMessage message = message(
                "00000000-0000-0000-0000-000000000001", "ticket.created");
        appendInTransaction(message);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return repository.claimPending(1, "owner-a", Instant.now().plusSeconds(3600));
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return repository.claimPending(1, "owner-b", Instant.now().plusSeconds(3600));
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<OutboxClaim> firstClaims = first.get(10, TimeUnit.SECONDS);
            List<OutboxClaim> secondClaims = second.get(10, TimeUnit.SECONDS);
            assertThat(firstClaims.size() + secondClaims.size()).isEqualTo(1);
        }
    }

    @Test
    void expiredLeaseReclaimsWithADifferentTokenForTheSameOwner() {
        OutboxMessage message = message(
                "00000000-0000-0000-0000-000000000001", "ticket.created");
        appendInTransaction(message);

        OutboxClaim oldClaim = repository.claimPending(
                        10, "owner-a", Instant.now().minusSeconds(1))
                .getFirst();
        OutboxClaim newClaim = repository.claimPending(
                        10, "owner-a", Instant.now().plusSeconds(3600))
                .getFirst();

        assertThat(newClaim.claimToken()).isNotEqualTo(oldClaim.claimToken());
        assertThat(jdbc.queryForObject("""
                select claim_token from outbox_event where id = ?
                """, String.class, message.id())).isEqualTo(newClaim.claimToken());
        assertAttempts(message, 2);
    }

    @Test
    void anOldClaimTokenCannotPublishOrReleaseAfterReclaim() {
        OutboxMessage message = message(
                "00000000-0000-0000-0000-000000000001", "ticket.created");
        appendInTransaction(message);
        OutboxClaim oldClaim = repository.claimPending(
                        10, "owner-a", Instant.now().minusSeconds(1))
                .getFirst();
        OutboxClaim currentClaim = repository.claimPending(
                        10, "owner-a", Instant.now().plusSeconds(3600))
                .getFirst();

        assertThatThrownBy(() -> repository.markPublished(
                        message.id(), "owner-a", oldClaim.claimToken()))
                .isInstanceOf(OutboxStateException.class);
        assertThatThrownBy(() -> repository.releaseForRetry(
                        message.id(), "owner-a", oldClaim.claimToken(),
                        Instant.now().plusSeconds(300), "stale"))
                .isInstanceOf(OutboxStateException.class);

        assertThat(jdbc.queryForObject("""
                select state from outbox_event where id = ?
                """, String.class, message.id())).isEqualTo("IN_FLIGHT");
        assertThat(jdbc.queryForObject("""
                select claim_token from outbox_event where id = ?
                """, String.class, message.id())).isEqualTo(currentClaim.claimToken());
    }

    @Test
    void claimsMessagesInStableCreatedAtThenIdOrder() {
        OutboxMessage laterId = message(
                "00000000-0000-0000-0000-000000000002", "ticket.second");
        OutboxMessage earlierId = message(
                "00000000-0000-0000-0000-000000000001", "ticket.first");
        appendInTransaction(laterId);
        appendInTransaction(earlierId);
        Timestamp createdAt = Timestamp.from(Instant.parse("2025-02-01T00:00:00Z"));
        jdbc.update("update outbox_event set created_at = ?", createdAt);

        assertThat(repository.claimPending(
                        10, "lease-owner", Instant.now().plusSeconds(3600)))
                .extracting(claim -> claim.message().id())
                .containsExactly(earlierId.id(), laterId.id());
    }

    @Test
    void claimsMessagesForDifferentTenantsWithoutLosingTenantIdentity() {
        OutboxMessage tenantB = message(
                "00000000-0000-0000-0000-000000000001", "tenant-b", "ticket.first");
        OutboxMessage tenantA = message(
                "00000000-0000-0000-0000-000000000002", "tenant-a", "ticket.second");
        appendInTransaction(tenantA);
        appendInTransaction(tenantB);
        jdbc.update(
                "update outbox_event set created_at = ?",
                Timestamp.from(Instant.parse("2025-02-01T00:00:00Z")));

        assertClaimsContainExactly(
                repository.claimPending(10, "lease-owner", Instant.now().plusSeconds(3600)),
                tenantB,
                tenantA);
    }

    @Test
    void rejectsMalformedJsonThroughTheMysqlJsonColumn() {
        OutboxMessage malformed = message(
                "00000000-0000-0000-0000-000000000001",
                "tenant-a",
                "ticket.created",
                "{\"ticketId\":\"ticket-1\"");

        assertThatThrownBy(() -> appendInTransaction(malformed))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject(
                "select count(*) from outbox_event", Integer.class)).isZero();
    }

    @Test
    void currentTokenPublishesAndClearsTheLease() {
        OutboxMessage message = message(
                "00000000-0000-0000-0000-000000000001", "ticket.created");
        appendInTransaction(message);
        OutboxClaim claim = repository.claimPending(
                        10, "owner-a", Instant.now().plusSeconds(3600))
                .getFirst();

        assertThatThrownBy(() -> repository.markPublished(
                        message.id(), "owner-b", claim.claimToken()))
                .isInstanceOf(OutboxStateException.class);
        repository.markPublished(message.id(), "owner-a", claim.claimToken());

        assertThat(jdbc.queryForObject("""
                select state from outbox_event where id = ?
                """, String.class, message.id())).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("""
                select published_at from outbox_event where id = ?
                """, Timestamp.class, message.id())).isNotNull();
        assertThat(jdbc.queryForObject("""
                select lease_owner from outbox_event where id = ?
                """, String.class, message.id())).isNull();
        assertThat(jdbc.queryForObject("""
                select claim_token from outbox_event where id = ?
                """, String.class, message.id())).isNull();
        assertThat(jdbc.queryForObject("""
                select lease_until from outbox_event where id = ?
                """, Timestamp.class, message.id())).isNull();
    }

    @Test
    void currentTokenReleasesAndSchedulesARetry() {
        OutboxMessage message = message(
                "00000000-0000-0000-0000-000000000001", "ticket.created");
        appendInTransaction(message);
        OutboxClaim claim = repository.claimPending(
                        10, "owner-a", Instant.now().plusSeconds(3600))
                .getFirst();
        Instant retryAt = Instant.now().plusSeconds(300).truncatedTo(ChronoUnit.MICROS);

        assertThatThrownBy(() -> repository.releaseForRetry(
                        message.id(), "owner-b", claim.claimToken(), retryAt, "boom"))
                .isInstanceOf(OutboxStateException.class);
        repository.releaseForRetry(
                message.id(), "owner-a", claim.claimToken(), retryAt, "boom");

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
                select claim_token from outbox_event where id = ?
                """, String.class, message.id())).isNull();
        assertThat(jdbc.queryForObject("""
                select lease_until from outbox_event where id = ?
                """, Timestamp.class, message.id())).isNull();
        assertThat(jdbc.queryForObject("""
                select available_at from outbox_event where id = ?
                """, Timestamp.class, message.id()).toInstant()).isEqualTo(retryAt);
    }

    @Test
    void appendFailsOutsideATransactionAndWritesNoRow() {
        OutboxMessage message = message(
                "00000000-0000-0000-0000-000000000001", "ticket.created");

        assertThatThrownBy(() -> repository.append(message))
                .isInstanceOf(OutboxTransactionRequiredException.class);

        assertThat(jdbc.queryForObject(
                "select count(*) from outbox_event", Integer.class)).isZero();
    }

    @Test
    void businessWriteAndOutboxAppendCommitTogether() {
        String tenantId = "tx-visible";
        OutboxMessage message = message(
                "00000000-0000-0000-0000-000000000001", tenantId, "tenant.created");

        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    insert into tenant(id, display_name, status)
                    values (?, 'Visible tenant', 'ACTIVE')
                    """, tenantId);
            repository.append(message);
        });

        assertThat(jdbc.queryForObject(
                "select count(*) from tenant where id = ?", Integer.class, tenantId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from outbox_event where id = ?", Integer.class, message.id())).isEqualTo(1);
    }

    @Test
    void businessWriteAndOutboxAppendRollBackTogether() {
        String tenantId = "tx-rolled-back";
        OutboxMessage message = message(
                "00000000-0000-0000-0000-000000000001", tenantId, "tenant.created");

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    insert into tenant(id, display_name, status)
                    values (?, 'Rolled back tenant', 'ACTIVE')
                    """, tenantId);
            repository.append(message);
            throw new IllegalStateException("force rollback");
        })).hasMessage("force rollback");

        assertThat(jdbc.queryForObject(
                "select count(*) from tenant where id = ?", Integer.class, tenantId)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from outbox_event where id = ?", Integer.class, message.id())).isZero();
    }

    private void appendInTransaction(OutboxMessage message) {
        transactions.executeWithoutResult(status -> repository.append(message));
    }

    private static OutboxMessage message(String id, String eventType) {
        return message(id, "tenant-a", eventType);
    }

    private static OutboxMessage message(String id, String tenantId, String eventType) {
        return message(
                id, tenantId, eventType, "{\"ticketId\":\"ticket-1\"}");
    }

    private static OutboxMessage message(
            String id, String tenantId, String eventType, String payloadJson) {
        return new OutboxMessage(
                id,
                new TenantId(tenantId),
                "ticket",
                "ticket-1",
                eventType,
                payloadJson,
                AVAILABLE_AT);
    }

    private static void assertClaimsContainExactly(
            List<OutboxClaim> actual, OutboxMessage... expected) {
        assertThat(actual).hasSameSizeAs(expected);
        assertThat(actual).extracting(OutboxClaim::claimToken)
                .allMatch(token -> token != null && !token.isBlank())
                .doesNotHaveDuplicates();
        for (int index = 0; index < expected.length; index++) {
            assertMessageFields(actual.get(index).message(), expected[index]);
        }
    }

    private void assertAttempts(OutboxMessage message, int expected) {
        assertThat(jdbc.queryForObject("""
                select attempts from outbox_event where id = ?
                """, Integer.class, message.id())).isEqualTo(expected);
    }

    private static void assertMessageFields(OutboxMessage actual, OutboxMessage expected) {
        assertThat(actual.id()).isEqualTo(expected.id());
        assertThat(actual.tenantId()).isEqualTo(expected.tenantId());
        assertThat(actual.aggregateType()).isEqualTo(expected.aggregateType());
        assertThat(actual.aggregateId()).isEqualTo(expected.aggregateId());
        assertThat(actual.eventType()).isEqualTo(expected.eventType());
        assertThat(readJson(actual.payloadJson())).isEqualTo(readJson(expected.payloadJson()));
        assertThat(actual.availableAt()).isEqualTo(expected.availableAt());
    }

    private static JsonNode readJson(String value) {
        try {
            return OBJECT_MAPPER.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new AssertionError("invalid JSON payload", exception);
        }
    }
}
