package com.echomind.saas.ticket.domain;

import java.time.Instant;
import java.util.Objects;

public record Ticket(
        TicketId ticketId,
        String customerId,
        String createdByUserId,
        String subject,
        String description,
        TicketStatus status,
        TicketPriority priority,
        String queueName,
        String assigneeUserId,
        String conversationId,
        String resolution,
        String commercialRefsJson,
        Instant slaDueAt,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant resolvedAt,
        Instant closedAt
) {
    public Ticket {
        ticketId = Objects.requireNonNull(ticketId, "ticketId");
        customerId = requireText(customerId, "customerId", 128);
        createdByUserId = requireText(createdByUserId, "createdByUserId", 128);
        subject = requireText(subject, "subject", 255);
        description = Objects.requireNonNull(description, "description");
        status = Objects.requireNonNull(status, "status");
        priority = Objects.requireNonNull(priority, "priority");
        queueName = optionalText(queueName, "queueName", 80);
        assigneeUserId = optionalText(assigneeUserId, "assigneeUserId", 128);
        conversationId = optionalText(conversationId, "conversationId", 128);
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must be nonblank and at most " + maxLength + " characters");
        }
        return value;
    }

    private static String optionalText(String value, String field, int maxLength) {
        if (value != null && (value.isBlank() || value.length() > maxLength)) {
            throw new IllegalArgumentException(
                    field + " must be nonblank and at most " + maxLength + " characters");
        }
        return value;
    }
}
