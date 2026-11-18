package com.echomind.saas.ticket.application;

import com.echomind.saas.ticket.domain.TicketPriority;

public record CreateTicketCommand(
        String customerId,
        String subject,
        String description,
        TicketPriority priority,
        String queueName,
        String conversationId,
        String commercialRefsJson,
        String idempotencyKey
) {
    private static final int MAX_COMMERCIAL_REFS_LENGTH = 65_535;

    public CreateTicketCommand {
        customerId = requireText(customerId, "customerId", 128);
        subject = requireText(subject, "subject", 255);
        description = requireText(description, "description", 20_000);
        priority = priority == null ? TicketPriority.NORMAL : priority;
        queueName = optionalText(queueName, "queueName", 80);
        conversationId = optionalText(conversationId, "conversationId", 128);
        commercialRefsJson = optionalText(
                commercialRefsJson, "commercialRefsJson", MAX_COMMERCIAL_REFS_LENGTH);
        if (idempotencyKey == null
                || !idempotencyKey.matches("[A-Za-z0-9._:-]{1,160}")) {
            throw new IllegalArgumentException(
                    "idempotencyKey must be 1-160 safe characters");
        }
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must be nonblank and at most " + maxLength + " characters");
        }
        return value;
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
