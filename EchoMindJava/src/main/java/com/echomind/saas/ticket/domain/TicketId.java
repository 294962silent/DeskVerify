package com.echomind.saas.ticket.domain;

public record TicketId(String value) {
    public TicketId {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("ticket id must be nonblank and at most 128 characters");
        }
    }
}
