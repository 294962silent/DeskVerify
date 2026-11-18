package com.echomind.saas.commerce.api;

import com.echomind.saas.commerce.domain.CommerceOrder;
import com.echomind.saas.commerce.domain.CommercialSnapshot;
import com.echomind.saas.commerce.domain.CustomerAccount;
import com.echomind.saas.commerce.domain.Entitlement;
import com.echomind.saas.commerce.domain.InvoiceSummary;
import com.echomind.saas.commerce.domain.Money;
import com.echomind.saas.commerce.domain.PaymentAttemptSummary;
import com.echomind.saas.commerce.domain.PlanSummary;
import com.echomind.saas.commerce.domain.SubscriptionSummary;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record CommerceResponse(
        AccountResponse account,
        List<OrderResponse> orders,
        PlanResponse currentPlan,
        List<EntitlementResponse> entitlements,
        SubscriptionResponse currentSubscription,
        List<InvoiceResponse> invoices,
        List<PaymentAttemptResponse> paymentAttempts
) {
    public CommerceResponse {
        account = Objects.requireNonNull(account, "account");
        orders = List.copyOf(Objects.requireNonNull(orders, "orders"));
        entitlements = List.copyOf(Objects.requireNonNull(entitlements, "entitlements"));
        invoices = List.copyOf(Objects.requireNonNull(invoices, "invoices"));
        paymentAttempts = List.copyOf(Objects.requireNonNull(paymentAttempts, "paymentAttempts"));
    }

    public static CommerceResponse from(CommercialSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new CommerceResponse(
                AccountResponse.from(snapshot.account()),
                snapshot.orders().stream().map(OrderResponse::from).toList(),
                snapshot.currentPlan().map(PlanResponse::from).orElse(null),
                snapshot.entitlements().stream().map(EntitlementResponse::from).toList(),
                snapshot.currentSubscription().map(SubscriptionResponse::from).orElse(null),
                snapshot.invoices().stream().map(InvoiceResponse::from).toList(),
                snapshot.paymentAttempts().stream().map(PaymentAttemptResponse::from).toList());
    }

    public record AccountResponse(
            String customerId,
            String displayName,
            String status,
            String externalRef
    ) {
        private static AccountResponse from(CustomerAccount account) {
            return new AccountResponse(
                    account.customerId().value(),
                    account.displayName(),
                    account.status(),
                    account.externalRef());
        }
    }

    public record OrderResponse(
            String orderId,
            String productCode,
            int quantity,
            MoneyResponse amount,
            String status,
            Instant orderedAt,
            Instant fulfilledAt,
            String subscriptionId
    ) {
        private static OrderResponse from(CommerceOrder order) {
            return new OrderResponse(
                    order.orderId(),
                    order.productCode(),
                    order.quantity(),
                    MoneyResponse.from(order.amount()),
                    order.status(),
                    order.orderedAt(),
                    order.fulfilledAt(),
                    order.subscriptionId());
        }
    }

    public record PlanResponse(
            String planId,
            String code,
            String displayName,
            MoneyResponse price,
            String billingInterval
    ) {
        private static PlanResponse from(PlanSummary plan) {
            return new PlanResponse(
                    plan.planId(),
                    plan.code(),
                    plan.displayName(),
                    MoneyResponse.from(plan.price()),
                    plan.billingInterval());
        }
    }

    public record EntitlementResponse(String code, String value) {
        private static EntitlementResponse from(Entitlement entitlement) {
            return new EntitlementResponse(entitlement.code(), entitlement.value());
        }
    }

    public record SubscriptionResponse(
            String subscriptionId,
            String planId,
            String status,
            Instant startedAt,
            Instant currentPeriodStart,
            Instant currentPeriodEnd
    ) {
        private static SubscriptionResponse from(SubscriptionSummary subscription) {
            return new SubscriptionResponse(
                    subscription.subscriptionId(),
                    subscription.planId(),
                    subscription.status(),
                    subscription.startedAt(),
                    subscription.currentPeriodStart(),
                    subscription.currentPeriodEnd());
        }
    }

    public record InvoiceResponse(
            String invoiceId,
            String status,
            MoneyResponse amount,
            Instant issuedAt,
            Instant dueAt,
            Instant paidAt
    ) {
        private static InvoiceResponse from(InvoiceSummary invoice) {
            return new InvoiceResponse(
                    invoice.invoiceId(),
                    invoice.status(),
                    MoneyResponse.from(invoice.amount()),
                    invoice.issuedAt(),
                    invoice.dueAt(),
                    invoice.paidAt());
        }
    }

    public record PaymentAttemptResponse(
            String paymentAttemptId,
            String invoiceId,
            String status,
            MoneyResponse amount,
            Instant attemptedAt,
            String failureCode
    ) {
        private static PaymentAttemptResponse from(PaymentAttemptSummary attempt) {
            return new PaymentAttemptResponse(
                    attempt.paymentAttemptId(),
                    attempt.invoiceId(),
                    attempt.status(),
                    MoneyResponse.from(attempt.amount()),
                    attempt.attemptedAt(),
                    attempt.failureCode());
        }
    }

    public record MoneyResponse(long amountMinor, String currency) {
        private static MoneyResponse from(Money money) {
            return new MoneyResponse(money.amountMinor(), money.currency());
        }
    }
}
