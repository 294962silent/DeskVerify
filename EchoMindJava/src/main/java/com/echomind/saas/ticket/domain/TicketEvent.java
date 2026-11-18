package com.echomind.saas.ticket.domain;

import java.time.Instant;
import java.util.Objects;

public record TicketEvent(
        String eventId,
        TicketId ticketId,
        String eventType,
        TicketStatus fromStatus,
        TicketStatus toStatus,
        String actorUserId,
        String reason,
        String payloadJson,
        Instant createdAt
) {
    public TicketEvent {
        eventId = requireText(eventId, "eventId", 128);
        ticketId = Objects.requireNonNull(ticketId, "ticketId");
        eventType = requireText(eventType, "eventType", 64);
        actorUserId = requireText(actorUserId, "actorUserId", 128);
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must be nonblank and at most " + maxLength + " characters");
        }
        return value;
    }
}
