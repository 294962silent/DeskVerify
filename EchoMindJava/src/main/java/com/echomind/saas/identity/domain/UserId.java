package com.echomind.saas.identity.domain;

public record UserId(String value) {
    public UserId {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("user id must be 1-128 safe characters");
        }
    }
}
