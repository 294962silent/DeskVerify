package com.echomind.saas.platform.persistence;

import java.sql.DriverManager;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationTest {

    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4").withDatabaseName("echomind");

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
}
