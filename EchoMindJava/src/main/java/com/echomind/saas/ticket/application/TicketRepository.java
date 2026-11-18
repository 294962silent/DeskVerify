package com.echomind.saas.ticket.application;

import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.ticket.domain.Ticket;
import com.echomind.saas.ticket.domain.TicketEvent;
import com.echomind.saas.ticket.domain.TicketId;
import java.util.List;
import java.util.Optional;

public interface TicketRepository {
    boolean customerExists(TenantId tenantId, String customerId);

    void insert(TenantId tenantId, Ticket ticket, List<TicketEvent> events);

    Optional<Ticket> find(TenantId tenantId, TicketId ticketId);

    List<Ticket> list(TenantId tenantId, TicketFilter filter);

    List<TicketEvent> events(TenantId tenantId, TicketId ticketId);

    boolean update(TenantId tenantId, Ticket current, Ticket next, TicketEvent event);
}
