package com.echomind.saas.ticket.application;

import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.ticket.domain.Ticket;

public interface TicketCommands {
    TicketCreationResult create(CreateTicketCommand command, ActorScope actor);

    Ticket claim(ClaimTicketCommand command, ActorScope actor);

    Ticket resolve(ResolveTicketCommand command, ActorScope actor);

    Ticket close(CloseTicketCommand command, ActorScope actor);

    Ticket reopen(ReopenTicketCommand command, ActorScope actor);
}
