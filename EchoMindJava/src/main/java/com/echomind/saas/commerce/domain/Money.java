package com.echomind.saas.commerce.domain;

public record Money(long amountMinor, String currency) {
    public Money {
        if (amountMinor < 0) {
            throw new IllegalArgumentException("amountMinor must be non-negative");
        }
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be an ISO-4217 code");
        }
    }
}
