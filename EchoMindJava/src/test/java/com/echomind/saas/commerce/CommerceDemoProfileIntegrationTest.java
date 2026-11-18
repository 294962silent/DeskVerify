package com.echomind.saas.commerce;

import com.echomind.EchoMindJavaApplication;
import com.echomind.saas.commerce.domain.CommercialSnapshot;
import com.echomind.saas.commerce.domain.CustomerId;
import com.echomind.saas.commerce.infrastructure.JdbcCommerceReadRepository;
import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.UserId;
import com.echomind.saas.identity.infrastructure.JdbcTenantMembershipRepository;
import com.echomind.saas.testsupport.MySqlTestContainers;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = EchoMindJavaApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureObservability
@ActiveProfiles({"anthropic", "demo"})
@Testcontainers(disabledWithoutDocker = true)
class CommerceDemoProfileIntegrationTest {

    private static final String TEST_JWT_SECRET =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Container
    static final MySQLContainer<?> MYSQL = MySqlTestContainers.create();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.ai.anthropic.api-key", () -> "test-placeholder");
        registry.add("echomind.security.jwt.hmac-secret", () -> TEST_JWT_SECRET);
        registry.add("echomind.storage.data-dir", () -> "target/commerce-demo/data");
        registry.add("echomind.storage.knowledge-path", () -> "target/commerce-demo/data/knowledge.json");
        registry.add("echomind.storage.memory-path", () -> "target/commerce-demo/data/memory.json");
        registry.add("echomind.eval.jobs-path", () -> "target/commerce-demo/data/eval-jobs.json");
        registry.add("echomind.skills.root-dir", () -> "target/commerce-demo/skills");
        registry.add("management.health.redis.enabled", () -> "false");
    }

    @Autowired
    private Flyway flyway;

    @Autowired
    private FlywayProperties flywayProperties;

    @Autowired
    private JdbcCommerceReadRepository commerceRepository;

    @Autowired
    private JdbcTenantMembershipRepository membershipRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Environment environment;

    @Test
    void demoProfileLoadsCompleteTenantScopedSnapshots() {
        assertThat(environment.getActiveProfiles()).contains("anthropic", "demo");
        assertThat(flywayProperties.getLocations())
                .containsExactly("classpath:db/migration", "classpath:db/demo");
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("100");

        List<String> tenants = List.of("acme", "globex", "initech");
        for (String tenant : tenants) {
            for (String customer : List.of("customer-001", "customer-002")) {
                CommercialSnapshot snapshot = commerceRepository
                        .load(new TenantId(tenant), new CustomerId(customer))
                        .orElseThrow();

                assertThat(snapshot.account().customerId().value()).isEqualTo(customer);
                assertThat(snapshot.orders()).isNotEmpty();
                assertThat(snapshot.orders())
                        .allSatisfy(order -> assertThat(order.orderId()).startsWith(tenant + "-"));
                assertThat(snapshot.currentPlan()).isPresent();
                assertThat(snapshot.entitlements()).isNotEmpty();
                assertThat(snapshot.currentSubscription()).hasValueSatisfying(subscription -> {
                    assertThat(subscription.status()).isEqualTo("ACTIVE");
                    assertThat(subscription.subscriptionId()).startsWith(tenant + "-");
                });
                assertThat(snapshot.invoices()).isNotEmpty();
                assertThat(snapshot.invoices()).allSatisfy(invoice ->
                        assertThat(invoice.invoiceId()).startsWith(tenant + "-"));
                assertThat(snapshot.paymentAttempts()).isNotEmpty();
                assertThat(snapshot.paymentAttempts()).allSatisfy(attempt -> {
                    assertThat(attempt.paymentAttemptId()).startsWith(tenant + "-");
                    assertThat(attempt.status()).isEqualTo("FAILED");
                    assertThat(attempt.failureCode()).isNotBlank();
                });
            }
        }

        CommercialSnapshot acme = snapshot("acme", "customer-001");
        CommercialSnapshot globex = snapshot("globex", "customer-001");
        CommercialSnapshot initech = snapshot("initech", "customer-001");
        assertThat(acme.account().displayName()).isEqualTo("Acme North America");
        assertThat(globex.account().displayName()).isEqualTo("Globex North America");
        assertThat(initech.account().displayName()).isEqualTo("Initech North America");
        assertThat(acme.invoices())
                .extracting(invoice -> invoice.status())
                .contains("OVERDUE");
    }

    @Test
    void demoUsersMapOnlyToTheirTenantCustomers() {
        assertTenantCountsAndMembership("acme", "demo-acme-user");
        assertTenantCountsAndMembership("globex", "demo-globex-user");
        assertTenantCountsAndMembership("initech", "demo-initech-user");
    }

    private CommercialSnapshot snapshot(String tenantId, String customerId) {
        return commerceRepository.load(
                        new TenantId(tenantId), new CustomerId(customerId))
                .orElseThrow();
    }

    private void assertMembership(String tenantId, String userId) {
        TenantId tenant = new TenantId(tenantId);
        UserId user = new UserId(userId);
        assertThat(membershipRepository.findRoles(tenant, user))
                .containsExactly(ActorRole.CUSTOMER);
        assertThat(membershipRepository.findCustomerIds(tenant, user))
                .containsExactlyInAnyOrder("customer-001", "customer-002");
    }

    private void assertTenantCountsAndMembership(String tenantId, String userId) {
        assertThat(jdbc.queryForObject(
                        "select count(*) from customer_account where tenant_id = ?",
                        Integer.class,
                        tenantId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select count(*) from customer_membership where tenant_id = ?",
                        Integer.class,
                        tenantId))
                .isEqualTo(2);
        assertMembership(tenantId, userId);
    }
}
