package com.echomind.saas.platform.outbox;

import java.util.List;
import java.time.Instant;

public interface OutboxRepository {
    void append(OutboxMessage message);

    List<OutboxMessage> claimPending(
            int limit,
            String leaseOwner,
            Instant leaseUntil
    );

    void markPublished(String messageId, String leaseOwner);

    void releaseForRetry(
            String messageId,
            String leaseOwner,
            Instant availableAt,
            String error
    );
}
