package com.echomind.saas.ticket.api;

import com.echomind.saas.ticket.domain.Ticket;
import com.echomind.saas.ticket.domain.TicketEvent;
import com.echomind.saas.ticket.domain.TicketPriority;
import com.echomind.saas.ticket.domain.TicketStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Objects;

public record TicketResponse(
        String ticketId,
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
        JsonNode commercialRefs,
        Instant slaDueAt,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant resolvedAt,
        Instant closedAt
) {
    public static TicketResponse from(Ticket ticket, ObjectMapper objectMapper) {
        Objects.requireNonNull(ticket, "ticket");
        return new TicketResponse(
                ticket.ticketId().value(),
                ticket.customerId(),
                ticket.createdByUserId(),
                ticket.subject(),
                ticket.description(),
                ticket.status(),
                ticket.priority(),
                ticket.queueName(),
                ticket.assigneeUserId(),
                ticket.conversationId(),
                ticket.resolution(),
                readJson(ticket.commercialRefsJson(), objectMapper),
                ticket.slaDueAt(),
                ticket.version(),
                ticket.createdAt(),
                ticket.updatedAt(),
                ticket.resolvedAt(),
                ticket.closedAt());
    }

    public record EventResponse(
            String eventId,
            String ticketId,
            String eventType,
            TicketStatus fromStatus,
            TicketStatus toStatus,
            String actorUserId,
            String reason,
            JsonNode payload,
            Instant createdAt
    ) {
        public static EventResponse from(TicketEvent event, ObjectMapper objectMapper) {
            Objects.requireNonNull(event, "event");
            return new EventResponse(
                    event.eventId(),
                    event.ticketId().value(),
                    event.eventType(),
                    event.fromStatus(),
                    event.toStatus(),
                    event.actorUserId(),
                    event.reason(),
                    readJson(event.payloadJson(), objectMapper),
                    event.createdAt());
        }
    }

    private static JsonNode readJson(String value, ObjectMapper objectMapper) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored ticket JSON is invalid", exception);
        }
    }
}
