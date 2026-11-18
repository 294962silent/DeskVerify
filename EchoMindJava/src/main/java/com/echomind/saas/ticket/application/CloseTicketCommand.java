package com.echomind.saas.ticket.application;

public record CloseTicketCommand(
        String ticketId,
        long expectedVersion
) {
    public CloseTicketCommand {
        ticketId = TicketCommandValidation.requireTicketId(ticketId);
        expectedVersion = TicketCommandValidation.requireVersion(expectedVersion);
    }
}
