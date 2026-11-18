package com.echomind.saas.commerce.infrastructure;

import com.echomind.saas.commerce.application.CommerceReadRepository;
import com.echomind.saas.commerce.domain.CommercialSnapshot;
import com.echomind.saas.commerce.domain.CommerceOrder;
import com.echomind.saas.commerce.domain.CustomerAccount;
import com.echomind.saas.commerce.domain.CustomerId;
import com.echomind.saas.commerce.domain.Entitlement;
import com.echomind.saas.commerce.domain.InvoiceSummary;
import com.echomind.saas.commerce.domain.Money;
import com.echomind.saas.commerce.domain.PaymentAttemptSummary;
import com.echomind.saas.commerce.domain.PlanSummary;
import com.echomind.saas.commerce.domain.SubscriptionSummary;
import com.echomind.saas.identity.domain.TenantId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCommerceReadRepository implements CommerceReadRepository {

    private final JdbcTemplate jdbc;

    public JdbcCommerceReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CommercialSnapshot> load(TenantId tenantId, CustomerId customerId) {
        Optional<CustomerAccount> account = loadAccount(tenantId, customerId);
        if (account.isEmpty()) {
            return Optional.empty();
        }

        List<CommerceOrder> orders = jdbc.query("""
                select order_id, product_code, quantity, amount_minor, currency, status,
                       ordered_at, fulfilled_at, subscription_id
                from commerce_order
                where tenant_id = ? and customer_id = ?
                order by ordered_at desc, order_id desc
                """, ORDER_MAPPER, tenantId.value(), customerId.value());

        Optional<SubscriptionSummary> subscription = loadCurrentSubscription(tenantId, customerId);
        Optional<PlanSummary> plan = Optional.empty();
        List<Entitlement> entitlements = List.of();
        if (subscription.isPresent()) {
            SubscriptionSummary selectedSubscription = subscription.orElseThrow();
            plan = Optional.of(loadPlan(
                    tenantId, customerId, selectedSubscription.subscriptionId()));
            entitlements = loadEntitlements(
                    tenantId, customerId, selectedSubscription.subscriptionId());
        }

        List<InvoiceSummary> invoices = loadInvoices(tenantId, customerId);
        List<PaymentAttemptSummary> paymentAttempts =
                loadPaymentAttempts(tenantId, customerId, invoices);

        return Optional.of(new CommercialSnapshot(
                account.orElseThrow(),
                orders,
                plan,
                entitlements,
                subscription,
                invoices,
                paymentAttempts));
    }

    private Optional<CustomerAccount> loadAccount(TenantId tenantId, CustomerId customerId) {
        return jdbc.query("""
                select customer_id, display_name, status, external_ref
                from customer_account
                where tenant_id = ? and customer_id = ?
                """, ACCOUNT_MAPPER, tenantId.value(), customerId.value())
                .stream()
                .findFirst();
    }

    private Optional<SubscriptionSummary> loadCurrentSubscription(
            TenantId tenantId,
            CustomerId customerId) {
        return jdbc.query("""
                select subscription_id, plan_id, status, started_at,
                       current_period_start, current_period_end
                from subscription
                where tenant_id = ? and customer_id = ?
                order by case when status = 'ACTIVE' then 0 else 1 end,
                         started_at desc, subscription_id desc
                limit 1
                """, SUBSCRIPTION_MAPPER, tenantId.value(), customerId.value())
                .stream()
                .findFirst();
    }

    private PlanSummary loadPlan(
            TenantId tenantId,
            CustomerId customerId,
            String subscriptionId) {
        return jdbc.queryForObject("""
                select p.plan_id, p.code, p.display_name, p.amount_minor,
                       p.currency, p.billing_interval
                from commerce_plan p
                join subscription s
                  on s.tenant_id = p.tenant_id and s.plan_id = p.plan_id
                where s.tenant_id = ? and s.customer_id = ? and s.subscription_id = ?
                """, PLAN_MAPPER, tenantId.value(), customerId.value(), subscriptionId);
    }

    private List<Entitlement> loadEntitlements(
            TenantId tenantId,
            CustomerId customerId,
            String subscriptionId) {
        return jdbc.query("""
                select e.code, e.entitlement_value
                from plan_entitlement e
                join subscription s
                  on s.tenant_id = e.tenant_id and s.plan_id = e.plan_id
                where s.tenant_id = ? and s.customer_id = ? and s.subscription_id = ?
                order by e.code
                """, ENTITLEMENT_MAPPER,
                tenantId.value(), customerId.value(), subscriptionId);
    }

    private List<InvoiceSummary> loadInvoices(TenantId tenantId, CustomerId customerId) {
        return jdbc.query("""
                select invoice_id, status, amount_minor, currency,
                       issued_at, due_at, paid_at
                from invoice
                where tenant_id = ? and customer_id = ?
                order by due_at desc, invoice_id desc
                """, INVOICE_MAPPER, tenantId.value(), customerId.value());
    }

    private List<PaymentAttemptSummary> loadPaymentAttempts(
            TenantId tenantId,
            CustomerId customerId,
            List<InvoiceSummary> invoices) {
        if (invoices.isEmpty()) {
            return List.of();
        }

        String placeholders = String.join(
                ", ", Collections.nCopies(invoices.size(), "?"));
        Object[] arguments = new Object[invoices.size() + 2];
        arguments[0] = tenantId.value();
        arguments[1] = customerId.value();
        for (int index = 0; index < invoices.size(); index++) {
            arguments[index + 2] = invoices.get(index).invoiceId();
        }

        return jdbc.query("""
                select payment_attempt_id, invoice_id, status, amount_minor,
                       currency, attempted_at, failure_code
                from payment_attempt
                where tenant_id = ? and customer_id = ?
                  and invoice_id in (%s)
                order by attempted_at desc, payment_attempt_id desc
                """.formatted(placeholders), PAYMENT_ATTEMPT_MAPPER, arguments);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static final RowMapper<CustomerAccount> ACCOUNT_MAPPER = (resultSet, rowNumber) ->
            new CustomerAccount(
                    new CustomerId(resultSet.getString("customer_id")),
                    resultSet.getString("display_name"),
                    resultSet.getString("status"),
                    resultSet.getString("external_ref"));

    private static final RowMapper<CommerceOrder> ORDER_MAPPER = (resultSet, rowNumber) ->
            new CommerceOrder(
                    resultSet.getString("order_id"),
                    resultSet.getString("product_code"),
                    resultSet.getInt("quantity"),
                    new Money(resultSet.getLong("amount_minor"), resultSet.getString("currency")),
                    resultSet.getString("status"),
                    instant(resultSet.getTimestamp("ordered_at")),
                    instant(resultSet.getTimestamp("fulfilled_at")),
                    resultSet.getString("subscription_id"));

    private static final RowMapper<PlanSummary> PLAN_MAPPER = (resultSet, rowNumber) ->
            new PlanSummary(
                    resultSet.getString("plan_id"),
                    resultSet.getString("code"),
                    resultSet.getString("display_name"),
                    new Money(resultSet.getLong("amount_minor"), resultSet.getString("currency")),
                    resultSet.getString("billing_interval"));

    private static final RowMapper<Entitlement> ENTITLEMENT_MAPPER =
            (resultSet, rowNumber) -> new Entitlement(
                    resultSet.getString("code"),
                    resultSet.getString("entitlement_value"));

    private static final RowMapper<SubscriptionSummary> SUBSCRIPTION_MAPPER =
            (resultSet, rowNumber) -> new SubscriptionSummary(
                    resultSet.getString("subscription_id"),
                    resultSet.getString("plan_id"),
                    resultSet.getString("status"),
                    instant(resultSet.getTimestamp("started_at")),
                    instant(resultSet.getTimestamp("current_period_start")),
                    instant(resultSet.getTimestamp("current_period_end")));

    private static final RowMapper<InvoiceSummary> INVOICE_MAPPER = (resultSet, rowNumber) ->
            new InvoiceSummary(
                    resultSet.getString("invoice_id"),
                    resultSet.getString("status"),
                    new Money(resultSet.getLong("amount_minor"), resultSet.getString("currency")),
                    instant(resultSet.getTimestamp("issued_at")),
                    instant(resultSet.getTimestamp("due_at")),
                    instant(resultSet.getTimestamp("paid_at")));

    private static final RowMapper<PaymentAttemptSummary> PAYMENT_ATTEMPT_MAPPER =
            (resultSet, rowNumber) -> new PaymentAttemptSummary(
                    resultSet.getString("payment_attempt_id"),
                    resultSet.getString("invoice_id"),
                    resultSet.getString("status"),
                    new Money(resultSet.getLong("amount_minor"), resultSet.getString("currency")),
                    instant(resultSet.getTimestamp("attempted_at")),
                    resultSet.getString("failure_code"));
}
