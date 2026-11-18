package com.echomind.saas.commerce.domain;

public record CustomerId(String value) {
    public CustomerId {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("customer id must be nonblank and at most 128 characters");
        }
    }
}
