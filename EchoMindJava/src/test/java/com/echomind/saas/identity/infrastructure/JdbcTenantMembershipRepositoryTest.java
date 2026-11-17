package com.echomind.saas.identity.infrastructure;

import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.UserId;
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
class JdbcTenantMembershipRepositoryTest {

    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4").withDatabaseName("echomind");

    private JdbcTenantMembershipRepository repository;

    @BeforeEach
    void setUp() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .cleanDisabled(false)
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.update("insert into tenant(id, display_name, status) values('tenant-a','A','ACTIVE')");
        jdbc.update("insert into app_user(id, display_name, status) values('user-1','U','ACTIVE')");
        jdbc.update("""
                insert into tenant_membership(tenant_id, user_id, status)
                values('tenant-a','user-1','ACTIVE')
                """);
        jdbc.update("""
                insert into role_binding(tenant_id, user_id, role_name)
                values('tenant-a','user-1','SUPPORT_AGENT')
                """);
        jdbc.update("""
                insert into customer_membership(tenant_id, user_id, customer_id, permission)
                values('tenant-a','user-1','customer-1','READ')
                """);
        repository = new JdbcTenantMembershipRepository(jdbc);
    }

    @Test
    void returnsOnlyActiveTenantRolesAndCustomers() {
        TenantId tenantId = new TenantId("tenant-a");
        UserId userId = new UserId("user-1");

        assertThat(repository.findRoles(tenantId, userId))
                .containsExactly(com.echomind.saas.identity.domain.ActorRole.SUPPORT_AGENT);
        assertThat(repository.findCustomerIds(tenantId, userId))
                .containsExactly("customer-1");
    }
}
