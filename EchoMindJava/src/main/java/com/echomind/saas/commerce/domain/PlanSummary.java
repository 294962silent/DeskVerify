package com.echomind.saas.commerce.domain;

public record PlanSummary(
        String planId,
        String code,
        String displayName,
        Money price,
        String billingInterval
) {
}
