package com.echomind.saas.platform.outbox;

import com.echomind.saas.identity.domain.TenantId;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public final class JdbcOutboxRepository implements OutboxRepository {

    private final JdbcTemplate jdbc;

    public JdbcOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
    public List<OutboxMessage> findPending(int limit) {
        return jdbc.query("""
                select id, tenant_id, aggregate_type, aggregate_id,
                       event_type, payload_json, available_at
                from outbox_event
                where state = 'PENDING' and available_at <= current_timestamp(6)
                order by created_at
                limit ?
                """, (result, row) -> new OutboxMessage(
                result.getString("id"),
                new TenantId(result.getString("tenant_id")),
                result.getString("aggregate_type"),
                result.getString("aggregate_id"),
                result.getString("event_type"),
                result.getString("payload_json"),
                result.getTimestamp("available_at").toInstant()
        ), limit);
    }
}
