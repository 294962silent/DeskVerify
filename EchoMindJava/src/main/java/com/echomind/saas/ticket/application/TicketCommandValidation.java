package com.echomind.saas.ticket.application;

final class TicketCommandValidation {

    private TicketCommandValidation() {
    }

    static String requireTicketId(String ticketId) {
        return requireText(ticketId, "ticketId", 128);
    }

    static long requireVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        return expectedVersion;
    }

    static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must be nonblank and at most " + maxLength + " characters");
        }
        return value;
    }
}
