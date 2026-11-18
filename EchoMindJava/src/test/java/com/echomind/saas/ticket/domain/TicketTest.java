package com.echomind.saas.ticket.domain;

import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketTest {

    private static final Instant CREATED_AT = Instant.parse("2026-11-18T01:02:03Z");

    @Test
    void preservesAValidTicket() {
        Ticket ticket = ticket("subject", "queue", "assignee", 0);

        assertThat(ticket.ticketId()).isEqualTo(new TicketId("ticket-1"));
        assertThat(ticket.subject()).isEqualTo("subject");
        assertThat(ticket.queueName()).isEqualTo("queue");
        assertThat(ticket.assigneeUserId()).isEqualTo("assignee");
        assertThat(ticket.version()).isZero();
    }

    @Test
    void rejectsInvalidRequiredTextAndNegativeVersion() {
        assertThatThrownBy(() -> ticket(" ", "queue", "assignee", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subject");
        assertThatThrownBy(() -> ticket("subject", "queue", "assignee", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }

    @Test
    void rejectsBlankOrOversizedOptionalText() {
        assertThatThrownBy(() -> ticket("subject", " ", "assignee", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queueName");
        assertThatThrownBy(() -> ticket("subject", "q".repeat(81), "assignee", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queueName");
    }

    private static Ticket ticket(
            String subject, String queueName, String assigneeUserId, long version) {
        return new Ticket(
                new TicketId("ticket-1"),
                "customer-1",
                "user-1",
                subject,
                "description",
                TicketStatus.OPEN,
                TicketPriority.NORMAL,
                queueName,
                assigneeUserId,
                null,
                null,
                null,
                null,
                version,
                CREATED_AT,
                CREATED_AT,
                null,
                null);
    }
}
