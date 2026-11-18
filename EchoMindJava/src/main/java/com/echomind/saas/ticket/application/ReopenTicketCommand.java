package com.echomind.saas.ticket.application;

public record ReopenTicketCommand(
        String ticketId,
        long expectedVersion,
        String reason
) {
    public ReopenTicketCommand {
        ticketId = TicketCommandValidation.requireTicketId(ticketId);
        expectedVersion = TicketCommandValidation.requireVersion(expectedVersion);
        reason = TicketCommandValidation.requireText(reason, "reason", 10_000);
    }
}
