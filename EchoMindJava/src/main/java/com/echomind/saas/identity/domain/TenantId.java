package com.echomind.saas.identity.domain;

public record TenantId(String value) {
    public TenantId {
        if (value == null || !value.matches("[a-z0-9][a-z0-9-]{1,62}")) {
            throw new IllegalArgumentException("tenant id must be 2-63 lowercase slug characters");
        }
    }
}
