package com.echomind.saas.commerce.domain;

public record CustomerAccount(
        CustomerId customerId,
        String displayName,
        String status,
        String externalRef
) {
}
