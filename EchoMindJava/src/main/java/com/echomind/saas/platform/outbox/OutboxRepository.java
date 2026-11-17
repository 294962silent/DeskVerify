package com.echomind.saas.platform.outbox;

import java.util.List;

public interface OutboxRepository {
    void append(OutboxMessage message);

    List<OutboxMessage> findPending(int limit);
}
