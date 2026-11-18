package com.echomind.saas.ticket.application;

import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.ticket.domain.Ticket;
import com.echomind.saas.ticket.domain.TicketEvent;
import java.util.List;

public interface TicketQueries {
    Ticket get(String ticketId, ActorScope actor);

    List<Ticket> list(TicketFilter filter, ActorScope actor);

    List<TicketEvent> events(String ticketId, ActorScope actor);
}
