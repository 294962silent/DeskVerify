package com.echomind.saas.ticket.application;

public final class TicketConflictException extends RuntimeException {
    public TicketConflictException() {
        super("ticket state conflict");
    }
}
