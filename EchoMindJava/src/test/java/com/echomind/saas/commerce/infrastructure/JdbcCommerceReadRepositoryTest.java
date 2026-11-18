package com.echomind.saas.commerce.infrastructure;

import com.echomind.saas.commerce.domain.CommercialSnapshot;
import com.echomind.saas.commerce.domain.CommerceOrder;
import com.echomind.saas.commerce.domain.CustomerId;
import com.echomind.saas.commerce.domain.Entitlement;
import com.echomind.saas.commerce.domain.InvoiceSummary;
import com.echomind.saas.commerce.domain.PaymentAttemptSummary;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.testsupport.MySqlTestContainers;
import java.sql.Timestamp;
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

@Testcontainers(disabledWithoutDocker = true)
class JdbcCommerceReadRepositoryTest {

    private static final Instant ORDER_A2_AT = Instant.parse("2025-01-02T10:00:00Z");
    private static final Instant ORDER_A1_AT = Instant.parse("2025-01-03T10:00:00Z");
    private static final Instant ACTIVE_STARTED_AT = Instant.parse("2025-02-01T00:00:00Z");
    private static final Instant HISTORICAL_STARTED_AT = Instant.parse("2025-03-01T00:00:00Z");

    @Container
    static final MySQLContainer<?> MYSQL = MySqlTestContainers.create();

    private JdbcTemplate jdbc;
    private JdbcCommerceReadRepository repository;

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
        repository = new JdbcCommerceReadRepository(jdbc);
        insertFixture();
    }

    @Test
    void loadsOnlyTheSelectedTenantAndCustomerInDeterministicOrder() {
        CommercialSnapshot snapshot = repository
                .load(new TenantId("tenant-a"), new CustomerId("customer-a"))
                .orElseThrow();

        assertThat(snapshot.account().customerId().value()).isEqualTo("customer-a");
        assertThat(snapshot.account().displayName()).isEqualTo("Acme China");
        assertThat(snapshot.account().status()).isEqualTo("ACTIVE");
        assertThat(snapshot.account().externalRef()).isEqualTo("crm-a");

        assertThat(snapshot.orders()).extracting(CommerceOrder::orderId)
                .containsExactly("order-a1", "order-a2");
        assertThat(snapshot.orders().getFirst().productCode()).isEqualTo("PRO-YEAR");
        assertThat(snapshot.orders().getFirst().quantity()).isEqualTo(1);
        assertThat(snapshot.orders().getFirst().amount().amountMinor()).isEqualTo(99_900L);
        assertThat(snapshot.orders().getFirst().amount().currency()).isEqualTo("CNY");
        assertThat(snapshot.orders().getFirst().status()).isEqualTo("PAID");
        assertThat(snapshot.orders().getFirst().orderedAt()).isEqualTo(ORDER_A1_AT);
        assertThat(snapshot.orders().getFirst().fulfilledAt()).isEqualTo(ORDER_A1_AT.plusSeconds(60));
        assertThat(snapshot.orders().getFirst().subscriptionId()).isEqualTo("subscription-a-active");

        assertThat(snapshot.currentPlan().planId()).isEqualTo("plan-a-pro");
        assertThat(snapshot.currentPlan().code()).isEqualTo("PRO");
        assertThat(snapshot.currentPlan().displayName()).isEqualTo("Pro");
        assertThat(snapshot.currentPlan().price().amountMinor()).isEqualTo(99_900L);
        assertThat(snapshot.currentPlan().billingInterval()).isEqualTo("YEAR");
        assertThat(snapshot.entitlements()).extracting(Entitlement::code)
                .containsExactly("seats", "sso");

        assertThat(snapshot.currentSubscription()).hasValueSatisfying(subscription -> {
            assertThat(subscription.subscriptionId()).isEqualTo("subscription-a-active");
            assertThat(subscription.planId()).isEqualTo("plan-a-pro");
            assertThat(subscription.status()).isEqualTo("ACTIVE");
            assertThat(subscription.startedAt()).isEqualTo(ACTIVE_STARTED_AT);
            assertThat(subscription.currentPeriodStart()).isEqualTo(ACTIVE_STARTED_AT);
            assertThat(subscription.currentPeriodEnd()).isEqualTo(ACTIVE_STARTED_AT.plusSeconds(31_536_000));
        });

        assertThat(snapshot.invoices()).extracting(InvoiceSummary::invoiceId)
                .containsExactly("invoice-a-late", "invoice-a-early");
        assertThat(snapshot.invoices().getFirst().amount().amountMinor()).isEqualTo(12_500L);
        assertThat(snapshot.invoices().getFirst().status()).isEqualTo("OPEN");

        assertThat(snapshot.paymentAttempts()).extracting(PaymentAttemptSummary::paymentAttemptId)
                .containsExactly("payment-a-2", "payment-a-1");
        assertThat(snapshot.paymentAttempts()).extracting(PaymentAttemptSummary::invoiceId)
                .containsOnly("invoice-a-late", "invoice-a-early");
        assertThat(snapshot.paymentAttempts().getFirst().status()).isEqualTo("FAILED");
        assertThat(snapshot.paymentAttempts().getFirst().failureCode()).isEqualTo("card_declined");
    }

    @Test
    void keepsTheSameCustomerSlugIndependentAcrossTenants() {
        CommercialSnapshot tenantB = repository
                .load(new TenantId("tenant-b"), new CustomerId("customer-a"))
                .orElseThrow();

        assertThat(tenantB.account().displayName()).isEqualTo("Acme Global");
        assertThat(tenantB.orders()).extracting(CommerceOrder::orderId)
                .containsExactly("order-b1");
        assertThat(tenantB.invoices()).extracting(InvoiceSummary::invoiceId)
                .containsExactly("invoice-b1");
        assertThat(tenantB.paymentAttempts()).extracting(PaymentAttemptSummary::paymentAttemptId)
                .containsExactly("payment-b1");
    }

    @Test
    void returnsEmptyWhenTheAccountIsNotVisible() {
        assertThat(repository.load(new TenantId("tenant-a"), new CustomerId("customer-missing")))
                .isEmpty();
    }

    @Test
    void prefersAnActiveSubscriptionEvenWhenAHistoricalOneIsNewer() {
        CommercialSnapshot snapshot = repository
                .load(new TenantId("tenant-a"), new CustomerId("customer-a"))
                .orElseThrow();

        assertThat(snapshot.currentSubscription().orElseThrow().subscriptionId())
                .isEqualTo("subscription-a-active");
    }

    @Test
    void loadsTheCurrentPlanWhenAnotherSubscriptionSharesThatPlan() {
        CommercialSnapshot snapshot = repository
                .load(new TenantId("tenant-a"), new CustomerId("customer-a"))
                .orElseThrow();

        assertThat(snapshot.currentPlan().planId()).isEqualTo("plan-a-pro");
    }

    @Test
    void fallsBackToTheMostRecentSubscriptionWhenNoneAreActive() {
        jdbc.update("update subscription set status = 'CANCELED' where tenant_id = 'tenant-a'");

        CommercialSnapshot snapshot = repository
                .load(new TenantId("tenant-a"), new CustomerId("customer-a"))
                .orElseThrow();

        assertThat(snapshot.currentSubscription().orElseThrow().subscriptionId())
                .isEqualTo("subscription-a-historical");
        assertThat(snapshot.currentPlan().planId()).isEqualTo("plan-a-basic");
    }

    private void insertFixture() {
        insertAccount("tenant-a", "customer-a", "Acme China", "crm-a");
        insertAccount("tenant-b", "customer-a", "Acme Global", "crm-b");
        insertAccount("tenant-a", "customer-b", "Beta China", "crm-c");

        insertPlan("tenant-a", "plan-a-pro", "PRO", "Pro", "YEAR", 99_900L);
        insertPlan("tenant-a", "plan-a-basic", "BASIC", "Basic", "MONTH", 1_900L);
        insertPlan("tenant-b", "plan-b-team", "TEAM", "Team", "MONTH", 2_900L);
        insertEntitlement("tenant-a", "plan-a-pro", "sso", "enabled");
        insertEntitlement("tenant-a", "plan-a-pro", "seats", "10");

        insertSubscription("tenant-a", "subscription-a-active", "customer-a", "plan-a-pro",
                "ACTIVE", ACTIVE_STARTED_AT);
        insertSubscription("tenant-a", "subscription-a-historical", "customer-a", "plan-a-basic",
                "CANCELED", HISTORICAL_STARTED_AT);
        insertSubscription("tenant-a", "subscription-a-old-pro", "customer-a", "plan-a-pro",
                "CANCELED", ACTIVE_STARTED_AT.minusSeconds(86_400));
        insertSubscription("tenant-b", "subscription-b-active", "customer-a", "plan-b-team",
                "ACTIVE", HISTORICAL_STARTED_AT);

        insertOrder("tenant-a", "order-a2", "customer-a", "PRO-MONTH", 1, 8_900L,
                ORDER_A2_AT, null, null);
        insertOrder("tenant-a", "order-a1", "customer-a", "PRO-YEAR", 1, 99_900L,
                ORDER_A1_AT, ORDER_A1_AT.plusSeconds(60), "subscription-a-active");
        insertOrder("tenant-b", "order-b1", "customer-a", "TEAM-MONTH", 2, 5_800L,
                ORDER_A1_AT, null, null);
        insertOrder("tenant-a", "order-c1", "customer-b", "BASIC-MONTH", 1, 1_900L,
                ORDER_A2_AT, null, null);

        insertInvoice("tenant-a", "invoice-a-early", "customer-a", "subscription-a-historical",
                1_900L, "PAID", "2025-01-31T00:00:00Z", "2025-02-01T00:00:00Z");
        insertInvoice("tenant-a", "invoice-a-late", "customer-a", "subscription-a-active",
                12_500L, "OPEN", "2025-03-31T00:00:00Z", null);
        insertInvoice("tenant-b", "invoice-b1", "customer-a", "subscription-b-active",
                2_900L, "PAID", "2025-03-31T00:00:00Z", "2025-03-31T00:00:00Z");
        insertInvoice("tenant-a", "invoice-c1", "customer-b", "subscription-a-active",
                50_000L, "OPEN", "2025-04-30T00:00:00Z", null);

        insertPaymentAttempt("tenant-a", "payment-a-1", "customer-a", "invoice-a-early",
                1_900L, "SUCCEEDED", "2025-02-01T00:00:00Z", null);
        insertPaymentAttempt("tenant-a", "payment-a-2", "customer-a", "invoice-a-late",
                12_500L, "FAILED", "2025-04-01T00:00:00Z", "card_declined");
        insertPaymentAttempt("tenant-b", "payment-b1", "customer-a", "invoice-b1",
                2_900L, "SUCCEEDED", "2025-03-31T00:00:00Z", null);
        insertPaymentAttempt("tenant-a", "payment-c1", "customer-b", "invoice-c1",
                50_000L, "FAILED", "2025-05-01T00:00:00Z", "insufficient_funds");
    }

    private void insertAccount(String tenantId, String customerId, String displayName, String externalRef) {
        jdbc.update("""
                insert into customer_account(tenant_id, customer_id, display_name, status, external_ref)
                values(?, ?, ?, 'ACTIVE', ?)
                """, tenantId, customerId, displayName, externalRef);
    }

    private void insertPlan(
            String tenantId,
            String planId,
            String code,
            String displayName,
            String billingInterval,
            long amountMinor) {
        jdbc.update("""
                insert into commerce_plan(
                    tenant_id, plan_id, code, display_name, billing_interval,
                    amount_minor, currency, status
                ) values(?, ?, ?, ?, ?, ?, 'CNY', 'ACTIVE')
                """, tenantId, planId, code, displayName, billingInterval, amountMinor);
    }

    private void insertEntitlement(String tenantId, String planId, String code, String value) {
        jdbc.update("""
                insert into plan_entitlement(tenant_id, plan_id, code, entitlement_value)
                values(?, ?, ?, ?)
                """, tenantId, planId, code, value);
    }

    private void insertSubscription(
            String tenantId,
            String subscriptionId,
            String customerId,
            String planId,
            String status,
            Instant startedAt) {
        jdbc.update("""
                insert into subscription(
                    tenant_id, subscription_id, customer_id, plan_id, status,
                    started_at, current_period_start, current_period_end
                ) values(?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, subscriptionId, customerId, planId, status,
                Timestamp.from(startedAt), Timestamp.from(startedAt),
                Timestamp.from(startedAt.plusSeconds(31_536_000)));
    }

    private void insertOrder(
            String tenantId,
            String orderId,
            String customerId,
            String productCode,
            int quantity,
            long amountMinor,
            Instant orderedAt,
            Instant fulfilledAt,
            String subscriptionId) {
        jdbc.update("""
                insert into commerce_order(
                    tenant_id, order_id, customer_id, product_code, quantity,
                    amount_minor, currency, status, ordered_at, fulfilled_at, subscription_id
                ) values(?, ?, ?, ?, ?, ?, 'CNY', 'PAID', ?, ?, ?)
                """, tenantId, orderId, customerId, productCode, quantity, amountMinor,
                Timestamp.from(orderedAt),
                fulfilledAt == null ? null : Timestamp.from(fulfilledAt),
                subscriptionId);
    }

    private void insertInvoice(
            String tenantId,
            String invoiceId,
            String customerId,
            String subscriptionId,
            long amountMinor,
            String status,
            String dueAt,
            String paidAt) {
        jdbc.update("""
                insert into invoice(
                    tenant_id, invoice_id, customer_id, subscription_id,
                    amount_minor, currency, status, issued_at, due_at, paid_at
                ) values(?, ?, ?, ?, ?, 'CNY', ?, ?, ?, ?)
                """, tenantId, invoiceId, customerId, subscriptionId, amountMinor, status,
                Timestamp.from(Instant.parse(dueAt).minusSeconds(86_400)),
                Timestamp.from(Instant.parse(dueAt)),
                paidAt == null ? null : Timestamp.from(Instant.parse(paidAt)));
    }

    private void insertPaymentAttempt(
            String tenantId,
            String paymentAttemptId,
            String customerId,
            String invoiceId,
            long amountMinor,
            String status,
            String attemptedAt,
            String failureCode) {
        jdbc.update("""
                insert into payment_attempt(
                    tenant_id, payment_attempt_id, customer_id, invoice_id,
                    amount_minor, currency, status, attempted_at, failure_code
                ) values(?, ?, ?, ?, ?, 'CNY', ?, ?, ?)
                """, tenantId, paymentAttemptId, customerId, invoiceId, amountMinor, status,
                Timestamp.from(Instant.parse(attemptedAt)), failureCode);
    }
}
