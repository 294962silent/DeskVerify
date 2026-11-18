package com.echomind.saas.ticket.domain;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketStateMachineTest {

    private final TicketStateMachine stateMachine = new TicketStateMachine();

    @Test
    void ticketStatusesMatchTheWorkflowVocabulary() {
        assertThat(EnumSet.allOf(TicketStatus.class)).containsExactly(
                TicketStatus.OPEN,
                TicketStatus.QUEUED,
                TicketStatus.CLAIMED,
                TicketStatus.RESOLVED,
                TicketStatus.CLOSED,
                TicketStatus.REOPENED);
    }

    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    void claimIsAllowedOnlyFromClaimableStatuses(TicketStatus status) {
        if (Set.of(TicketStatus.OPEN, TicketStatus.QUEUED, TicketStatus.REOPENED).contains(status)) {
            assertThatNoException().isThrownBy(() -> stateMachine.requireClaimable(status));
            return;
        }

        assertInvalidTransition("claim", status, () -> stateMachine.requireClaimable(status));
    }

    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    void resolveIsAllowedOnlyFromClaimed(TicketStatus status) {
        if (status == TicketStatus.CLAIMED) {
            assertThatNoException().isThrownBy(() -> stateMachine.requireResolvable(status));
            return;
        }

        assertInvalidTransition("resolve", status, () -> stateMachine.requireResolvable(status));
    }

    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    void closeIsAllowedOnlyFromResolved(TicketStatus status) {
        if (status == TicketStatus.RESOLVED) {
            assertThatNoException().isThrownBy(() -> stateMachine.requireClosable(status));
            return;
        }

        assertInvalidTransition("close", status, () -> stateMachine.requireClosable(status));
    }

    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    void reopenIsAllowedOnlyFromResolvedOrClosed(TicketStatus status) {
        if (Set.of(TicketStatus.RESOLVED, TicketStatus.CLOSED).contains(status)) {
            assertThatNoException().isThrownBy(() -> stateMachine.requireReopenable(status));
            return;
        }

        assertInvalidTransition("reopen", status, () -> stateMachine.requireReopenable(status));
    }

    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    void queueIsAllowedOnlyFromOpenOrReopened(TicketStatus status) {
        if (Set.of(TicketStatus.OPEN, TicketStatus.REOPENED).contains(status)) {
            assertThatNoException().isThrownBy(() -> stateMachine.requireQueueable(status));
            return;
        }

        assertInvalidTransition("queue", status, () -> stateMachine.requireQueueable(status));
    }

    private static void assertInvalidTransition(
            String operation, TicketStatus status, Runnable transition) {
        assertThatThrownBy(transition::run)
                .isInstanceOf(InvalidTicketTransitionException.class)
                .hasMessageContaining(operation)
                .hasMessageContaining(status.name());
    }
}
