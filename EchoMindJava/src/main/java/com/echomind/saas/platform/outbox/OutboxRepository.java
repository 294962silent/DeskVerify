package com.echomind.saas.platform.outbox;

import java.time.Instant;
import java.util.List;

public interface OutboxRepository {
    void append(OutboxMessage message);

    List<OutboxClaim> claimPending(
            int limit,
            String leaseOwner,
            Instant leaseUntil
    );

    void markPublished(
            String messageId,
            String leaseOwner,
            String claimToken
    );

    void releaseForRetry(
            String messageId,
            String leaseOwner,
            String claimToken,
            Instant availableAt,
            String error
    );
}
