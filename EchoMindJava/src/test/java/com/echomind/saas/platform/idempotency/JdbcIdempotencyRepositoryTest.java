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
    void acceptsOnceAndRejectsAReusedKeyWithDifferentPayload() {
        TenantId tenantId = new TenantId("tenant-a");
        Instant expiresAt = Instant.now().plusSeconds(3600);

        assertThat(repository.tryAcquire(tenantId, "key-1", "hash-a", expiresAt)).isTrue();
        assertThat(repository.tryAcquire(tenantId, "key-1", "hash-a", expiresAt)).isFalse();
        assertThatThrownBy(
                () -> repository.tryAcquire(tenantId, "key-1", "hash-b", expiresAt))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void scopesKeysAndCompletionByTenant() {
        TenantId tenantA = new TenantId("tenant-a");
        TenantId tenantB = new TenantId("tenant-b");
        Instant expiresAt = Instant.now().plusSeconds(3600);

        assertThat(repository.tryAcquire(tenantA, "key-1", "hash-a", expiresAt)).isTrue();
        assertThat(repository.tryAcquire(tenantB, "key-1", "hash-b", expiresAt)).isTrue();

        repository.complete(tenantA, "key-1", "{\"status\":\"ok\"}");

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
                select state from idempotency_record
                where tenant_id = 'tenant-b' and idempotency_key = 'key-1'
                """, String.class)).isEqualTo("IN_PROGRESS");
    }
}
