package com.echomind.saas.ticket.api;

import com.echomind.saas.ticket.domain.TicketPriority;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TicketCreateRequest(
        @NotBlank @Size(max = 128) String customerId,
        @NotBlank @Size(max = 255) String subject,
        @NotBlank @Size(max = 20_000) String description,
        TicketPriority priority,
        @Size(max = 80) String queueName,
        @Size(max = 128) String conversationId,
        JsonNode commercialRefs
) {
}
