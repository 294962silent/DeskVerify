package com.echomind.saas.ticket.application;

import com.echomind.saas.ticket.domain.TicketStateMachine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TicketApplicationConfiguration {

    @Bean
    TicketStateMachine ticketStateMachine() {
        return new TicketStateMachine();
    }

    @Bean
    TicketSlaPolicy ticketSlaPolicy() {
        return new TicketSlaPolicy();
    }
}
