package com.echomind.saas.ticket.domain;

import java.util.Objects;

public final class InvalidTicketTransitionException extends RuntimeException {

    private final String operation;
    private final TicketStatus status;

    public InvalidTicketTransitionException(String operation, TicketStatus status) {
        super("cannot %s ticket in status %s".formatted(
                Objects.requireNonNull(operation, "operation"),
                Objects.requireNonNull(status, "status")));
        this.operation = operation;
        this.status = status;
    }

    public String operation() {
        return operation;
    }

    public TicketStatus status() {
        return status;
    }
}
