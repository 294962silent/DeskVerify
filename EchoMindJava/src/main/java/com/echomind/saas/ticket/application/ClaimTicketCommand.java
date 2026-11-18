package com.echomind.saas.ticket.application;

public record ClaimTicketCommand(
        String ticketId,
        long expectedVersion
) {
    public ClaimTicketCommand {
        ticketId = TicketCommandValidation.requireTicketId(ticketId);
        expectedVersion = TicketCommandValidation.requireVersion(expectedVersion);
    }
}
