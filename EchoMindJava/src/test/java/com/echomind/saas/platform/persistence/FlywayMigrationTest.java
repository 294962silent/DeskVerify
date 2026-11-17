package com.echomind.saas.platform.persistence;

import com.echomind.saas.testsupport.MySqlTestContainers;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = MySqlTestContainers.create();

    @Test
    void appliesIdentityAndMessagingSchema() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load()
                .migrate();

        try (var connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            var tables = List.of("tenant", "tenant_membership", "outbox_event", "inbox_event");
            for (String table : tables) {
                try (var statement = connection.prepareStatement(
                        "select count(*) from information_schema.tables " +
                        "where table_schema = database() and table_name = ?")) {
                    statement.setString(1, table);
                    try (var result = statement.executeQuery()) {
                        result.next();
                        assertThat(result.getInt(1)).isEqualTo(1);
                    }
                }
            }
        }
    }

    @Test
    void inboxDeduplicationIsScopedByTenant() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("delete from inbox_event");
            }
            insertInboxEvent(connection, "consumer-1", "event-1", "tenant-a");
            insertInboxEvent(connection, "consumer-1", "event-1", "tenant-b");

            assertThatThrownBy(() ->
                    insertInboxEvent(connection, "consumer-1", "event-1", "tenant-a"))
                    .hasMessageContaining("Duplicate entry");

            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("select count(*) from inbox_event")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(2);
            }
        }
    }

    private static void insertInboxEvent(
            Connection connection, String consumer, String eventId, String tenantId)
            throws Exception {
        try (var statement = connection.prepareStatement("""
                insert into inbox_event(consumer_name, event_id, tenant_id)
                values (?, ?, ?)
                """)) {
            statement.setString(1, consumer);
            statement.setString(2, eventId);
            statement.setString(3, tenantId);
            statement.executeUpdate();
        }
    }
}
