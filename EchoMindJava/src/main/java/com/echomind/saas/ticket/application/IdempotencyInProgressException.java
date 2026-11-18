package com.echomind.saas.ticket.application;

public final class IdempotencyInProgressException extends RuntimeException {
    public IdempotencyInProgressException() {
        super("request is still in progress");
    }
}
