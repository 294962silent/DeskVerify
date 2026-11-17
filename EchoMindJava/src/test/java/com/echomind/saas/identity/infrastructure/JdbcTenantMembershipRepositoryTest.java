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

    private JdbcTemplate jdbc;
    private JdbcTenantMembershipRepository repository;

    @BeforeEach
    void setUp() {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
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

    @Test
    void excludesAnotherTenantForTheSameUser() {
        jdbc.update("insert into tenant(id, display_name, status) values('tenant-b','B','ACTIVE')");
        jdbc.update("""
                insert into tenant_membership(tenant_id, user_id, status)
                values('tenant-b','user-1','ACTIVE')
                """);
        jdbc.update("""
                insert into role_binding(tenant_id, user_id, role_name)
                values('tenant-b','user-1','TENANT_ADMIN')
                """);
        jdbc.update("""
                insert into customer_membership(tenant_id, user_id, customer_id, permission)
                values('tenant-b','user-1','customer-2','READ')
                """);

        TenantId selectedTenant = new TenantId("tenant-a");
        TenantId otherTenant = new TenantId("tenant-b");
        UserId userId = new UserId("user-1");

        assertThat(repository.findRoles(selectedTenant, userId))
                .containsExactly(com.echomind.saas.identity.domain.ActorRole.SUPPORT_AGENT);
        assertThat(repository.findCustomerIds(selectedTenant, userId))
                .containsExactly("customer-1");
        assertThat(repository.findRoles(otherTenant, userId))
                .containsExactly(com.echomind.saas.identity.domain.ActorRole.TENANT_ADMIN);
        assertThat(repository.findCustomerIds(otherTenant, userId))
                .containsExactly("customer-2");
    }

    @Test
    void filtersRoleBindingsAndCustomersForAnInactiveMembership() {
        jdbc.update("insert into app_user(id, display_name, status) values('user-2','U2','ACTIVE')");
        jdbc.update("""
                insert into tenant_membership(tenant_id, user_id, status)
                values('tenant-a','user-2','INACTIVE')
                """);
        jdbc.update("""
                insert into role_binding(tenant_id, user_id, role_name)
                values('tenant-a','user-2','TENANT_ADMIN')
                """);
        jdbc.update("""
                insert into customer_membership(tenant_id, user_id, customer_id, permission)
                values('tenant-a','user-2','customer-2','READ')
                """);

        assertThat(jdbc.queryForObject("""
                select count(*) from role_binding
                where tenant_id = 'tenant-a' and user_id = 'user-2'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from customer_membership
                where tenant_id = 'tenant-a' and user_id = 'user-2'
                """, Integer.class)).isEqualTo(1);

        TenantId tenantId = new TenantId("tenant-a");
        UserId userId = new UserId("user-2");

        assertThat(repository.findRoles(tenantId, userId)).isEmpty();
        assertThat(repository.findCustomerIds(tenantId, userId)).isEmpty();
    }
}
