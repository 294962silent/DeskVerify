package com.echomind.saas.platform.idempotency;

import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.testsupport.MySqlTestContainers;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
class JdbcIdempotencyRepositoryTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Container
    static final MySQLContainer<?> MYSQL = MySqlTestContainers.create();

    private JdbcIdempotencyRepository repository;
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
        jdbc.update("delete from idempotency_record");
        repository = new JdbcIdempotencyRepository(jdbc);
    }

    @Test
    void returnsEmptyWhenTheSameActiveKeyAndHashAreReused() {
        TenantId tenantId = new TenantId("tenant-a");
        Instant expiresAt = Instant.now().plusSeconds(3600);

        assertThat(repository.tryAcquire(tenantId, "key-1", "hash-a", expiresAt)).isPresent();
        assertThat(repository.tryAcquire(tenantId, "key-1", "hash-a", expiresAt)).isEmpty();
    }

    @Test
    void rejectsAReusedKeyWithADifferentHash() {
        TenantId tenantId = new TenantId("tenant-a");
        Instant expiresAt = Instant.now().plusSeconds(3600);

        repository.tryAcquire(tenantId, "key-1", "hash-a", expiresAt);

        assertThatThrownBy(
                () -> repository.tryAcquire(tenantId, "key-1", "hash-b", expiresAt))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void reclaimsAnExpiredRecordWithANewFencingToken() {
        TenantId tenantId = new TenantId("tenant-a");
        String originalToken = repository.tryAcquire(
                        tenantId, "key-1", "hash-a", Instant.now().minusSeconds(1))
                .orElseThrow();

        String reclaimedToken = repository.tryAcquire(
                        tenantId, "key-1", "hash-b", Instant.now().plusSeconds(3600))
                .orElseThrow();

        assertThat(reclaimedToken).isNotEqualTo(originalToken);
        assertThat(jdbc.queryForObject("""
                select request_hash from idempotency_record
                where tenant_id = 'tenant-a' and idempotency_key = 'key-1'
                """, String.class)).isEqualTo("hash-b");
    }

    @Test
    void anOldAttemptTokenCannotCompleteAfterReclaim() {
        TenantId tenantId = new TenantId("tenant-a");
        String oldToken = repository.tryAcquire(
                        tenantId, "key-1", "hash-a", Instant.now().minusSeconds(1))
                .orElseThrow();
        String currentToken = repository.tryAcquire(
                        tenantId, "key-1", "hash-a", Instant.now().plusSeconds(3600))
                .orElseThrow();

        assertThatThrownBy(
                () -> repository.complete(tenantId, "key-1", oldToken, "{\"status\":\"stale\"}"))
                .isInstanceOf(IdempotencyStateException.class);
        assertThat(jdbc.queryForObject("""
                select state from idempotency_record
                where tenant_id = 'tenant-a' and idempotency_key = 'key-1'
                """, String.class)).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("""
                select attempt_token from idempotency_record
                where tenant_id = 'tenant-a' and idempotency_key = 'key-1'
                """, String.class)).isEqualTo(currentToken);
    }

    @Test
    void theCurrentTokenCompletesAndStoresTheResponse() {
        TenantId tenantId = new TenantId("tenant-a");
        String attemptToken = repository.tryAcquire(
                        tenantId, "key-1", "hash-a", Instant.now().plusSeconds(3600))
                .orElseThrow();

        repository.complete(tenantId, "key-1", attemptToken, "{\"status\":\"ok\"}");

        assertThat(jdbc.queryForObject("""
                select state from idempotency_record
                where tenant_id = 'tenant-a' and idempotency_key = 'key-1'
                """, String.class)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("""
                select json_unquote(json_extract(response_json, '$.status'))
                from idempotency_record
                where tenant_id = 'tenant-a' and idempotency_key = 'key-1'
                """, String.class)).isEqualTo("ok");
        assertThat(jdbc.queryForObject("""
                select attempt_token from idempotency_record
                where tenant_id = 'tenant-a' and idempotency_key = 'key-1'
                """, String.class)).isNull();
    }

    @Test
    void scopesKeysAndCompletionByTenant() {
        TenantId tenantA = new TenantId("tenant-a");
        TenantId tenantB = new TenantId("tenant-b");
        Instant expiresAt = Instant.now().plusSeconds(3600);
        String tokenA = repository.tryAcquire(tenantA, "key-1", "hash-a", expiresAt).orElseThrow();
        repository.tryAcquire(tenantB, "key-1", "hash-b", expiresAt).orElseThrow();

        repository.complete(tenantA, "key-1", tokenA, "{\"status\":\"ok\"}");

        assertThat(jdbc.queryForObject("""
                select state from idempotency_record
                where tenant_id = 'tenant-b' and idempotency_key = 'key-1'
                """, String.class)).isEqualTo("IN_PROGRESS");
    }

    @Test
    void completedReplayReturnsResponseAndCompletionTime() throws Exception {
        TenantId tenantId = new TenantId("tenant-a");
        String attemptToken = repository.tryAcquire(
                        tenantId, "key-1", "hash-a", Instant.now().plusSeconds(3600))
                .orElseThrow();

        repository.complete(tenantId, "key-1", attemptToken, "{\"status\":\"ok\"}");

        IdempotencyReplay replay = repository.findCompleted(tenantId, "key-1").orElseThrow();
        assertThat(OBJECT_MAPPER.readTree(replay.responseJson()))
                .isEqualTo(OBJECT_MAPPER.readTree("{\"status\":\"ok\"}"));
        assertThat(replay.completedAt()).isNotNull();
    }

    @Test
    void activeInProgressRecordHasNoCompletedReplay() {
        TenantId tenantId = new TenantId("tenant-a");
        repository.tryAcquire(
                tenantId, "key-1", "hash-a", Instant.now().plusSeconds(3600));

        assertThat(repository.findCompleted(tenantId, "key-1")).isEmpty();
    }

    @Test
    void expiredCompletedRecordHasNoReplay() {
        TenantId tenantId = new TenantId("tenant-a");
        String attemptToken = repository.tryAcquire(
                        tenantId, "key-1", "hash-a", Instant.now().plusSeconds(3600))
                .orElseThrow();
        repository.complete(tenantId, "key-1", attemptToken, "{\"status\":\"ok\"}");
        jdbc.update("""
                update idempotency_record
                set expires_at = ?
                where tenant_id = 'tenant-a' and idempotency_key = 'key-1'
                """, Timestamp.from(Instant.now().minusSeconds(1)));

        assertThat(repository.findCompleted(tenantId, "key-1")).isEmpty();
    }

    @Test
    void completedReplayIsTenantIsolated() {
        TenantId tenantA = new TenantId("tenant-a");
        TenantId tenantB = new TenantId("tenant-b");
        String attemptToken = repository.tryAcquire(
                        tenantA, "key-1", "hash-a", Instant.now().plusSeconds(3600))
                .orElseThrow();
        repository.complete(tenantA, "key-1", attemptToken, "{\"status\":\"ok\"}");

        assertThat(repository.findCompleted(tenantB, "key-1")).isEmpty();
    }

    @Test
    void concurrentAcquisitionsWithTheSameHashReturnAtMostOneToken() throws Exception {
        TenantId tenantId = new TenantId("tenant-a");
        Instant expiresAt = Instant.now().plusSeconds(3600);

        List<ConcurrentOutcome<Optional<String>>> outcomes = runConcurrently(
                () -> repository.tryAcquire(tenantId, "key-1", "hash-a", expiresAt),
                () -> repository.tryAcquire(tenantId, "key-1", "hash-a", expiresAt));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.failure()).isNull());
        assertThat(outcomes.stream()
                .map(ConcurrentOutcome::value)
                .filter(Optional::isPresent)
                .count()).isEqualTo(1);
    }

    @Test
    void concurrentAcquisitionsWithDifferentHashesProduceOneTokenAndOneConflict()
            throws Exception {
        TenantId tenantId = new TenantId("tenant-a");
        Instant expiresAt = Instant.now().plusSeconds(3600);

        List<ConcurrentOutcome<Optional<String>>> outcomes = runConcurrently(
                () -> repository.tryAcquire(tenantId, "key-1", "hash-a", expiresAt),
                () -> repository.tryAcquire(tenantId, "key-1", "hash-b", expiresAt));

        assertThat(outcomes.stream().filter(outcome -> outcome.failure() == null).count())
                .isEqualTo(1);
        assertThat(outcomes.stream()
                .filter(outcome -> outcome.failure() == null)
                .map(ConcurrentOutcome::value)
                .filter(Optional::isPresent)
                .count()).isEqualTo(1);
        assertThat(outcomes.stream()
                .filter(outcome -> outcome.failure() != null)
                .map(ConcurrentOutcome::failure)
                .toList()).singleElement()
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void concurrentExpiredReclaimsReturnExactlyOneTokenAndOnlyItCanComplete()
            throws Exception {
        TenantId tenantId = new TenantId("tenant-a");
        String oldToken = repository.tryAcquire(
                        tenantId, "key-1", "hash-a", Instant.now().minusSeconds(60))
                .orElseThrow();
        Instant newExpiry = Instant.now().plusSeconds(3600);

        List<ConcurrentOutcome<Optional<String>>> outcomes = runConcurrently(
                () -> repository.tryAcquire(tenantId, "key-1", "hash-a", newExpiry),
                () -> repository.tryAcquire(tenantId, "key-1", "hash-a", newExpiry));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.failure()).isNull());
        List<String> tokens = outcomes.stream()
                .map(ConcurrentOutcome::value)
                .flatMap(Optional::stream)
                .toList();
        assertThat(tokens).hasSize(1);
        String currentToken = tokens.getFirst();
        assertThat(currentToken).isNotEqualTo(oldToken);

        assertThatThrownBy(() -> repository.complete(
                tenantId, "key-1", oldToken, "{\"status\":\"stale\"}"))
                .isInstanceOf(IdempotencyStateException.class);
        repository.complete(tenantId, "key-1", currentToken, "{\"status\":\"ok\"}");
        assertThat(jdbc.queryForObject("""
                select state from idempotency_record
                where tenant_id = 'tenant-a' and idempotency_key = 'key-1'
                """, String.class)).isEqualTo("COMPLETED");
    }

    private static <T> List<ConcurrentOutcome<T>> runConcurrently(
            Callable<T> first, Callable<T> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ConcurrentOutcome<T>>> futures = List.of(
                executor.submit(() -> awaitStartAndRun(first, ready, start)),
                executor.submit(() -> awaitStartAndRun(second, ready, start)));
        try {
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrent calls did not become ready");
            }
            start.countDown();
            return futures.stream().map(future -> {
                try {
                    return future.get(20, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError("concurrent call did not finish", exception);
                }
            }).toList();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static <T> ConcurrentOutcome<T> awaitStartAndRun(
            Callable<T> task, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent start was not released");
            }
            return new ConcurrentOutcome<>(task.call(), null);
        } catch (Throwable failure) {
            return new ConcurrentOutcome<>(null, failure);
        }
    }

    private record ConcurrentOutcome<T>(T value, Throwable failure) {
    }
}
