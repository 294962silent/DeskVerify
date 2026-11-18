package com.echomind.saas.ticket.domain;

import com.echomind.saas.testsupport.MySqlTestContainers;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class TicketSchemaTest {

    @Container
    static final MySQLContainer<?> MYSQL = MySqlTestContainers.create();

    @Test
    void migratesTicketTablesWithRequiredColumnsAndDefaults() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            assertThat(latestVersion(connection)).isEqualTo("11");
            assertThat(tableNames(connection)).contains("ticket", "ticket_event");
            assertThat(columnNames(connection, "ticket")).contains(
                    "tenant_id",
                    "ticket_id",
                    "customer_id",
                    "created_by_user_id",
                    "subject",
                    "description",
                    "status",
                    "priority",
                    "queue_name",
                    "assignee_user_id",
                    "conversation_id",
                    "resolution",
                    "commercial_refs_json",
                    "sla_due_at",
                    "version",
                    "created_at",
                    "updated_at",
                    "resolved_at",
                    "closed_at");
            assertThat(columnNames(connection, "ticket_event")).contains(
                    "tenant_id",
                    "event_id",
                    "ticket_id",
                    "event_type",
                    "from_status",
                    "to_status",
                    "actor_user_id",
                    "reason",
                    "payload_json",
                    "created_at");
            assertThat(columnDefinition(connection, "ticket", "version"))
                    .isEqualTo(new ColumnDefinition("bigint", "NO", "0"));
            assertThat(columnDefinition(connection, "ticket", "commercial_refs_json").dataType())
                    .isEqualTo("json");
            assertThat(columnDefinition(connection, "ticket_event", "payload_json").dataType())
                    .isEqualTo("json");
        }
    }

    @Test
    void ticketKeysForeignKeysAndIndexesAreTenantLeading() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            assertThat(primaryKeyColumns(connection, "ticket"))
                    .containsExactly("tenant_id", "ticket_id");
            assertThat(primaryKeyColumns(connection, "ticket_event"))
                    .containsExactly("tenant_id", "event_id");
            assertThat(foreignKey(connection, "ticket", "fk_ticket_customer"))
                    .containsExactly(
                            "tenant_id->customer_account.tenant_id",
                            "customer_id->customer_account.customer_id");
            assertThat(foreignKey(connection, "ticket_event", "fk_ticket_event_ticket"))
                    .containsExactly(
                            "tenant_id->ticket.tenant_id",
                            "ticket_id->ticket.ticket_id");
            assertThat(indexColumns(connection, "ticket", "idx_ticket_status_created"))
                    .containsExactly("tenant_id", "status", "created_at");
            assertThat(indexColumns(connection, "ticket", "idx_ticket_assignee_status"))
                    .containsExactly("tenant_id", "assignee_user_id", "status");
            assertThat(indexColumns(connection, "ticket", "idx_ticket_customer_created"))
                    .containsExactly("tenant_id", "customer_id", "created_at");
            assertThat(indexColumns(connection, "ticket_event", "idx_ticket_event_ticket_created"))
                    .containsExactly("tenant_id", "ticket_id", "created_at");
            assertThat(indexColumns(
                            connection,
                            "knowledge_document_version",
                            "uk_knowledge_version_checksum_model"))
                    .containsExactly(
                            "tenant_id",
                            "document_id",
                            "source_checksum",
                            "embedding_model");
        }
    }

    @Test
    void rejectsCrossTenantTicketOwnershipAndEventReferences() throws Exception {
        migrate();

        try (Connection connection = connect()) {
            seedTenantAndCustomer(connection, "tenant-a", "customer-a", "user-a");
            seedTenantAndCustomer(connection, "tenant-b", "customer-b", "user-b");
            insertTicket(connection, "tenant-a", "ticket-a", "customer-a", "user-a");

            assertThatThrownBy(() ->
                    insertTicket(connection, "tenant-b", "ticket-cross", "customer-a", "user-b"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("foreign key");

            insertEvent(connection, "tenant-a", "event-a", "ticket-a", "user-a");
            assertThatThrownBy(() ->
                    insertEvent(connection, "tenant-b", "event-cross", "ticket-a", "user-b"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("foreign key");
        }
    }

    private static void migrate() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load()
                .migrate();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static String latestVersion(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select version from flyway_schema_history
                where success = 1 order by installed_rank desc limit 1
                """); var result = statement.executeQuery()) {
            result.next();
            return result.getString(1);
        }
    }

    private static Set<String> tableNames(Connection connection) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        try (var statement = connection.prepareStatement("""
                select table_name from information_schema.tables
                where table_schema = database()
                """); var result = statement.executeQuery()) {
            while (result.next()) {
                names.add(result.getString(1));
            }
        }
        return names;
    }

    private static Set<String> columnNames(Connection connection, String table)
            throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        try (var statement = connection.prepareStatement("""
                select column_name from information_schema.columns
                where table_schema = database() and table_name = ?
                order by ordinal_position
                """)) {
            statement.setString(1, table);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    names.add(result.getString(1));
                }
            }
        }
        return names;
    }

    private static ColumnDefinition columnDefinition(
            Connection connection, String table, String column) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select data_type, is_nullable, column_default
                from information_schema.columns
                where table_schema = database() and table_name = ? and column_name = ?
                """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (var result = statement.executeQuery()) {
                result.next();
                return new ColumnDefinition(
                        result.getString("data_type"),
                        result.getString("is_nullable"),
                        result.getString("column_default"));
            }
        }
    }

    private static List<String> primaryKeyColumns(Connection connection, String table)
            throws SQLException {
        List<String> columns = new ArrayList<>();
        try (var statement = connection.prepareStatement("""
                select column_name from information_schema.key_column_usage
                where table_schema = database() and table_name = ?
                    and constraint_name = 'PRIMARY'
                order by ordinal_position
                """)) {
            statement.setString(1, table);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    columns.add(result.getString(1));
                }
            }
        }
        return columns;
    }

    private static List<String> foreignKey(
            Connection connection, String table, String constraint) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (var statement = connection.prepareStatement("""
                select column_name, referenced_table_name, referenced_column_name
                from information_schema.key_column_usage
                where table_schema = database() and table_name = ?
                    and constraint_name = ?
                order by ordinal_position
                """)) {
            statement.setString(1, table);
            statement.setString(2, constraint);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    columns.add(result.getString(1) + "->"
                            + result.getString(2) + "." + result.getString(3));
                }
            }
        }
        return columns;
    }

    private static List<String> indexColumns(
            Connection connection, String table, String index) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (var statement = connection.prepareStatement("""
                select column_name from information_schema.statistics
                where table_schema = database() and table_name = ? and index_name = ?
                order by seq_in_index
                """)) {
            statement.setString(1, table);
            statement.setString(2, index);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    columns.add(result.getString(1));
                }
            }
        }
        return columns;
    }

    private static void seedTenantAndCustomer(
            Connection connection, String tenantId, String customerId, String userId)
            throws SQLException {
        try (var tenant = connection.prepareStatement("""
                insert into tenant(id, display_name, status) values (?, ?, 'ACTIVE')
                """); var user = connection.prepareStatement("""
                insert into app_user(id, display_name, status) values (?, ?, 'ACTIVE')
                """); var membership = connection.prepareStatement("""
                insert into tenant_membership(tenant_id, user_id, status)
                values (?, ?, 'ACTIVE')
                """); var customer = connection.prepareStatement("""
                insert into customer_account(tenant_id, customer_id, display_name, status)
                values (?, ?, ?, 'ACTIVE')
                """)) {
            tenant.setString(1, tenantId);
            tenant.setString(2, tenantId);
            tenant.executeUpdate();
            user.setString(1, userId);
            user.setString(2, userId);
            user.executeUpdate();
            membership.setString(1, tenantId);
            membership.setString(2, userId);
            membership.executeUpdate();
            customer.setString(1, tenantId);
            customer.setString(2, customerId);
            customer.setString(3, customerId);
            customer.executeUpdate();
        }
    }

    private static void insertTicket(
            Connection connection,
            String tenantId,
            String ticketId,
            String customerId,
            String userId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into ticket(
                    tenant_id, ticket_id, customer_id, created_by_user_id, subject,
                    description, status, priority
                ) values (?, ?, ?, ?, 'subject', 'description', 'OPEN', 'NORMAL')
                """)) {
            statement.setString(1, tenantId);
            statement.setString(2, ticketId);
            statement.setString(3, customerId);
            statement.setString(4, userId);
            statement.executeUpdate();
        }
    }

    private static void insertEvent(
            Connection connection,
            String tenantId,
            String eventId,
            String ticketId,
            String userId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into ticket_event(
                    tenant_id, event_id, ticket_id, event_type,
                    from_status, to_status, actor_user_id
                ) values (?, ?, ?, 'ticket.created', null, 'OPEN', ?)
                """)) {
            statement.setString(1, tenantId);
            statement.setString(2, eventId);
            statement.setString(3, ticketId);
            statement.setString(4, userId);
            statement.executeUpdate();
        }
    }

    private record ColumnDefinition(String dataType, String nullable, String defaultValue) {
    }
}
