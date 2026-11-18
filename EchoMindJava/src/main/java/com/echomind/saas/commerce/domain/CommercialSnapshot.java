package com.echomind.saas.commerce.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record CommercialSnapshot(
        CustomerAccount account,
        List<CommerceOrder> orders,
        Optional<PlanSummary> currentPlan,
        List<Entitlement> entitlements,
        Optional<SubscriptionSummary> currentSubscription,
        List<InvoiceSummary> invoices,
        List<PaymentAttemptSummary> paymentAttempts
) {
    public CommercialSnapshot {
        account = Objects.requireNonNull(account, "account");
        orders = List.copyOf(Objects.requireNonNull(orders, "orders"));
        currentPlan = Objects.requireNonNull(currentPlan, "currentPlan");
        entitlements = List.copyOf(Objects.requireNonNull(entitlements, "entitlements"));
        currentSubscription = Objects.requireNonNull(currentSubscription, "currentSubscription");
        invoices = List.copyOf(Objects.requireNonNull(invoices, "invoices"));
        paymentAttempts = List.copyOf(Objects.requireNonNull(paymentAttempts, "paymentAttempts"));
    }
}
