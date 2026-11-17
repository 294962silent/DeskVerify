package com.echomind.saas.platform.idempotency;

import com.echomind.saas.identity.domain.TenantId;
import java.time.Instant;
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

    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4").withDatabaseName("echomind");

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
}
