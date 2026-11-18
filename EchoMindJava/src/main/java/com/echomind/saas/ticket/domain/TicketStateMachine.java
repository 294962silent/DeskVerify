package com.echomind.saas.ticket.domain;

import java.util.Objects;
import java.util.Set;

public final class TicketStateMachine {

    private static final Set<TicketStatus> CLAIMABLE = Set.of(
            TicketStatus.OPEN,
            TicketStatus.QUEUED,
            TicketStatus.REOPENED);
    private static final Set<TicketStatus> RESOLVABLE = Set.of(TicketStatus.CLAIMED);
    private static final Set<TicketStatus> CLOSABLE = Set.of(TicketStatus.RESOLVED);
    private static final Set<TicketStatus> REOPENABLE = Set.of(
            TicketStatus.RESOLVED,
            TicketStatus.CLOSED);
    private static final Set<TicketStatus> QUEUEABLE = Set.of(
            TicketStatus.OPEN,
            TicketStatus.REOPENED);

    public void requireClaimable(TicketStatus status) {
        requireAllowed("claim", status, CLAIMABLE);
    }

    public void requireResolvable(TicketStatus status) {
        requireAllowed("resolve", status, RESOLVABLE);
    }

    public void requireClosable(TicketStatus status) {
        requireAllowed("close", status, CLOSABLE);
    }

    public void requireReopenable(TicketStatus status) {
        requireAllowed("reopen", status, REOPENABLE);
    }

    public void requireQueueable(TicketStatus status) {
        requireAllowed("queue", status, QUEUEABLE);
    }

    private static void requireAllowed(
            String operation, TicketStatus status, Set<TicketStatus> allowedStatuses) {
        Objects.requireNonNull(status, "status");
        if (!allowedStatuses.contains(status)) {
            throw new InvalidTicketTransitionException(operation, status);
        }
    }
}
