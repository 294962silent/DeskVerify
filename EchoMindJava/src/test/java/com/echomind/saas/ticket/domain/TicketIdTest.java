package com.echomind.saas.ticket.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketIdTest {

    @Test
    void preservesAValidTicketId() {
        assertThat(new TicketId("ticket-1").value()).isEqualTo("ticket-1");
    }

    @Test
    void rejectsMissingBlankAndOversizedIds() {
        assertThatThrownBy(() -> new TicketId(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TicketId(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TicketId("t".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
