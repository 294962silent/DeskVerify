package com.echomind.saas.platform.outbox;

public record OutboxClaim(OutboxMessage message, String claimToken) {
    public OutboxClaim {
        java.util.Objects.requireNonNull(message, "message");
        if (claimToken == null || claimToken.isBlank()) {
            throw new IllegalArgumentException("claimToken is required");
        }
    }
}
