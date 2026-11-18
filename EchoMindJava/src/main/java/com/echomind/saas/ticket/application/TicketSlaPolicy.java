package com.echomind.saas.ticket.application;

import com.echomind.saas.ticket.domain.TicketPriority;
import java.time.Duration;
import java.time.Instant;

public final class TicketSlaPolicy {

    public Instant dueAt(TicketPriority priority, Instant from) {
        Duration duration = switch (priority) {
            case LOW -> Duration.ofHours(24);
            case NORMAL -> Duration.ofHours(8);
            case HIGH -> Duration.ofHours(2);
            case URGENT -> Duration.ofMinutes(30);
        };
        return from.plus(duration);
    }
}
