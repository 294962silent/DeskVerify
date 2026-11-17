package com.echomind.saas.platform.idempotency;

public final class IdempotencyStateException extends RuntimeException {
    public IdempotencyStateException(String message) {
        super(message);
    }
}
