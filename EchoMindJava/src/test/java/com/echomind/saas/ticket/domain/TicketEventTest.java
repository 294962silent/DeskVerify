package com.echomind.saas.ticket.domain;

import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketEventTest {

    @Test
    void preservesAValidEvent() {
        TicketEvent event = event("ticket.created");

        assertThat(event.eventId()).isEqualTo("event-1");
        assertThat(event.ticketId()).isEqualTo(new TicketId("ticket-1"));
        assertThat(event.eventType()).isEqualTo("ticket.created");
        assertThat(event.fromStatus()).isNull();
        assertThat(event.toStatus()).isEqualTo(TicketStatus.OPEN);
    }

    @Test
    void rejectsBlankOrOversizedRequiredText() {
        assertThatThrownBy(() -> event(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");
        assertThatThrownBy(() -> event("e".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");
    }

    private static TicketEvent event(String eventType) {
        return new TicketEvent(
                "event-1",
                new TicketId("ticket-1"),
                eventType,
                null,
                TicketStatus.OPEN,
                "user-1",
                null,
                null,
                Instant.parse("2026-11-18T01:02:03Z"));
    }
}
