package com.echomind.saas.platform.outbox;

import com.echomind.saas.identity.domain.TenantId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public final class JdbcOutboxRepository implements OutboxRepository {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource(), "jdbc data source")));
    }

    @Override
    public void append(OutboxMessage message) {
        jdbc.update("""
                insert into outbox_event(
                    id, tenant_id, aggregate_type, aggregate_id,
                    event_type, payload_json, state, available_at
                ) values (?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """,
                message.id(),
                message.tenantId().value(),
                message.aggregateType(),
                message.aggregateId(),
                message.eventType(),
                message.payloadJson(),
                Timestamp.from(message.availableAt()));
    }

    @Override
    public List<OutboxMessage> claimPending(
            int limit, String leaseOwner, Instant leaseUntil) {
        List<OutboxMessage> claimed = transactions.execute(status -> {
            List<String> ids = jdbc.query("""
                    select id
                    from outbox_event
                    where (state = 'PENDING' and available_at <= current_timestamp(6))
                       or (state = 'IN_FLIGHT' and lease_until <= current_timestamp(6))
                    order by created_at, id
                    limit ?
                    for update skip locked
                    """, (result, row) -> result.getString("id"), limit);
            if (ids.isEmpty()) {
                return List.of();
            }

            Timestamp leaseTimestamp = Timestamp.from(leaseUntil);
            for (String id : ids) {
                jdbc.update("""
                        update outbox_event
                        set state = 'IN_FLIGHT',
                            lease_owner = ?,
                            lease_until = ?,
                            attempts = attempts + 1
                        where id = ?
                        """, leaseOwner, leaseTimestamp, id);
            }

            String placeholders = String.join(", ",
                    java.util.Collections.nCopies(ids.size(), "?"));
            return jdbc.query("""
                    select id, tenant_id, aggregate_type, aggregate_id,
                           event_type, payload_json, available_at
                    from outbox_event
                    where id in (%s)
                    order by created_at, id
                    """.formatted(placeholders), (result, row) -> new OutboxMessage(
                    result.getString("id"),
                    new TenantId(result.getString("tenant_id")),
                    result.getString("aggregate_type"),
                    result.getString("aggregate_id"),
                    result.getString("event_type"),
                    result.getString("payload_json"),
                    result.getTimestamp("available_at").toInstant()
            ), ids.toArray());
        });
        return Objects.requireNonNull(claimed, "transaction result");
    }

    @Override
    public void markPublished(String messageId, String leaseOwner) {
        int updated = jdbc.update("""
                update outbox_event
                set state = 'PUBLISHED',
                    published_at = current_timestamp(6),
                    lease_owner = null,
                    lease_until = null
                where id = ?
                  and state = 'IN_FLIGHT'
                  and lease_owner = ?
                """, messageId, leaseOwner);
        if (updated != 1) {
            throw new OutboxStateException("outbox lease owner is no longer current");
        }
    }

    @Override
    public void releaseForRetry(
            String messageId, String leaseOwner, Instant availableAt, String error) {
        int updated = jdbc.update("""
                update outbox_event
                set state = 'PENDING',
                    available_at = ?,
                    lease_owner = null,
                    lease_until = null,
                    last_error = ?
                where id = ?
                  and state = 'IN_FLIGHT'
                  and lease_owner = ?
                """, Timestamp.from(availableAt), error, messageId, leaseOwner);
        if (updated != 1) {
            throw new OutboxStateException("outbox lease owner is no longer current");
        }
    }
}
