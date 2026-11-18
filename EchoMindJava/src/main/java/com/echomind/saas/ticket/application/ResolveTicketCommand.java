package com.echomind.saas.ticket.application;

public record ResolveTicketCommand(
        String ticketId,
        long expectedVersion,
        String resolution
) {
    public ResolveTicketCommand {
        ticketId = TicketCommandValidation.requireTicketId(ticketId);
        expectedVersion = TicketCommandValidation.requireVersion(expectedVersion);
        resolution = TicketCommandValidation.requireText(
                resolution, "resolution", 20_000);
    }
}
