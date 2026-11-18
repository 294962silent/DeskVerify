package com.echomind.saas.ticket.application;

import com.echomind.saas.ticket.domain.Ticket;
import java.util.Objects;

public record TicketCreationResult(
        Ticket ticket,
        boolean replayed
) {
    public TicketCreationResult {
        ticket = Objects.requireNonNull(ticket, "ticket");
    }
}
