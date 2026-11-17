package com.echomind.saas.platform.outbox;

import com.echomind.saas.identity.domain.TenantId;
import java.time.Instant;
import java.util.UUID;
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
class JdbcOutboxRepositoryTest {

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
    void persistsAPendingMessage() {
        OutboxMessage message = new OutboxMessage(
                UUID.randomUUID().toString(),
                new TenantId("tenant-a"),
                "ticket",
                "ticket-1",
                "ticket.created",
                "{\"ticketId\":\"ticket-1\"}",
                Instant.now()
        );

        repository.append(message);

        assertThat(repository.findPending(10))
                .extracting(OutboxMessage::eventType)
                .containsExactly("ticket.created");
    }

    @Test
    void excludesMessagesThatAreNotYetAvailable() {
        TenantId tenantId = new TenantId("tenant-a");
        OutboxMessage available = new OutboxMessage(
                UUID.randomUUID().toString(),
                tenantId,
                "ticket",
                "ticket-1",
                "ticket.available",
                "{\"ticketId\":\"ticket-1\"}",
                Instant.now()
        );
        OutboxMessage future = new OutboxMessage(
                UUID.randomUUID().toString(),
                tenantId,
                "ticket",
                "ticket-2",
                "ticket.future",
                "{\"ticketId\":\"ticket-2\"}",
                Instant.now().plusSeconds(3600)
        );

        repository.append(future);
        repository.append(available);

        assertThat(repository.findPending(10))
                .extracting(OutboxMessage::eventType)
                .containsExactly("ticket.available");
    }
}
