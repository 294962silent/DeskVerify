package com.echomind.saas.platform.outbox;

public final class OutboxStateException extends RuntimeException {
    public OutboxStateException(String message) {
        super(message);
    }
}
