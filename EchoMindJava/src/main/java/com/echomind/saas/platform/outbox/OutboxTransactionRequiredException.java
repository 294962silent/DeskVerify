package com.echomind.saas.platform.outbox;

public class OutboxTransactionRequiredException extends IllegalStateException {
    public OutboxTransactionRequiredException(String message) {
        super(message);
    }
}
