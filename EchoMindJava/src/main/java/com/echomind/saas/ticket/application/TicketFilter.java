package com.echomind.saas.ticket.application;

import com.echomind.saas.ticket.domain.TicketStatus;

public record TicketFilter(
        TicketStatus status,
        String customerId,
        String assigneeUserId,
        int limit
) {
    public TicketFilter {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        customerId = optionalText(customerId, "customerId", 128);
        assigneeUserId = optionalText(assigneeUserId, "assigneeUserId", 128);
    }

    private static String optionalText(String value, String field, int maxLength) {
        if (value == null) {
            return null;
        }
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must be nonblank and at most " + maxLength + " characters");
        }
        return value;
    }
}
