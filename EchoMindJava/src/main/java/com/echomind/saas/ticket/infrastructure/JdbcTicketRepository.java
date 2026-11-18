package com.echomind.saas.ticket.infrastructure;

import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.ticket.application.TicketFilter;
import com.echomind.saas.ticket.application.TicketRepository;
import com.echomind.saas.ticket.domain.Ticket;
import com.echomind.saas.ticket.domain.TicketEvent;
import com.echomind.saas.ticket.domain.TicketId;
import com.echomind.saas.ticket.domain.TicketPriority;
import com.echomind.saas.ticket.domain.TicketStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTicketRepository implements TicketRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcTicketRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean customerExists(TenantId tenantId, String customerId) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                from customer_account
                where tenant_id = ? and customer_id = ?
                """, Integer.class, tenantId.value(), customerId);
        return count != null && count == 1;
    }

    @Override
    public void insert(TenantId tenantId, Ticket ticket, List<TicketEvent> events) {
        validateEvents(ticket, events);
        jdbc.update("""
                insert into ticket(
                    tenant_id, ticket_id, customer_id, created_by_user_id,
                    subject, description, status, priority, queue_name,
                    assignee_user_id, conversation_id, resolution,
                    commercial_refs_json, sla_due_at, version,
                    created_at, updated_at, resolved_at, closed_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tenantId.value(),
                ticket.ticketId().value(),
                ticket.customerId(),
                ticket.createdByUserId(),
                ticket.subject(),
                ticket.description(),
                ticket.status().name(),
                ticket.priority().name(),
                ticket.queueName(),
                ticket.assigneeUserId(),
                ticket.conversationId(),
                ticket.resolution(),
                normalizeJson(ticket.commercialRefsJson()),
                timestamp(ticket.slaDueAt()),
                ticket.version(),
                timestamp(ticket.createdAt()),
                timestamp(ticket.updatedAt()),
                timestamp(ticket.resolvedAt()),
                timestamp(ticket.closedAt()));
        for (TicketEvent event : events) {
            insertEvent(tenantId, event);
        }
    }

    @Override
    public Optional<Ticket> find(TenantId tenantId, TicketId ticketId) {
        return jdbc.query("""
                select ticket_id, customer_id, created_by_user_id, subject, description,
                       status, priority, queue_name, assignee_user_id, conversation_id,
                       resolution, commercial_refs_json, sla_due_at, version,
                       created_at, updated_at, resolved_at, closed_at
                from ticket
                where tenant_id = ? and ticket_id = ?
                """, TICKET_MAPPER, tenantId.value(), ticketId.value())
                .stream()
                .map(this::normalizeTicket)
                .findFirst();
    }

    @Override
    public List<Ticket> list(TenantId tenantId, TicketFilter filter) {
        StringBuilder sql = new StringBuilder("""
                select ticket_id, customer_id, created_by_user_id, subject, description,
                       status, priority, queue_name, assignee_user_id, conversation_id,
                       resolution, commercial_refs_json, sla_due_at, version,
                       created_at, updated_at, resolved_at, closed_at
                from ticket
                where tenant_id = ?
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(tenantId.value());
        if (filter.status() != null) {
            sql.append(" and status = ?");
            arguments.add(filter.status().name());
        }
        if (filter.customerId() != null) {
            sql.append(" and customer_id = ?");
            arguments.add(filter.customerId());
        }
        if (filter.assigneeUserId() != null) {
            sql.append(" and assignee_user_id = ?");
            arguments.add(filter.assigneeUserId());
        }
        sql.append(" order by created_at desc, ticket_id desc limit ?");
        arguments.add(filter.limit());
        return jdbc.query(sql.toString(), TICKET_MAPPER, arguments.toArray())
                .stream()
                .map(this::normalizeTicket)
                .toList();
    }

    @Override
    public List<TicketEvent> events(TenantId tenantId, TicketId ticketId) {
        return jdbc.query("""
                select event_id, ticket_id, event_type, from_status, to_status,
                       actor_user_id, reason, payload_json, created_at
                from ticket_event
                where tenant_id = ? and ticket_id = ?
                order by created_at, event_id
                """, EVENT_MAPPER, tenantId.value(), ticketId.value())
                .stream()
                .map(this::normalizeEvent)
                .toList();
    }

    @Override
    public boolean update(
            TenantId tenantId, Ticket current, Ticket next, TicketEvent event) {
        validateEvents(current, List.of(event));
        if (!next.ticketId().equals(current.ticketId())
                || next.version() != current.version() + 1) {
            throw new IllegalArgumentException("ticket update must advance exactly one version");
        }
        int updated = jdbc.update("""
                update ticket
                set status = ?,
                    priority = ?,
                    queue_name = ?,
                    assignee_user_id = ?,
                    conversation_id = ?,
                    resolution = ?,
                    commercial_refs_json = ?,
                    sla_due_at = ?,
                    version = ?,
                    updated_at = ?,
                    resolved_at = ?,
                    closed_at = ?
                where tenant_id = ?
                  and ticket_id = ?
                  and version = ?
                  and status = ?
                """,
                next.status().name(),
                next.priority().name(),
                next.queueName(),
                next.assigneeUserId(),
                next.conversationId(),
                next.resolution(),
                normalizeJson(next.commercialRefsJson()),
                timestamp(next.slaDueAt()),
                next.version(),
                timestamp(next.updatedAt()),
                timestamp(next.resolvedAt()),
                timestamp(next.closedAt()),
                tenantId.value(),
                current.ticketId().value(),
                current.version(),
                current.status().name());
        if (updated != 1) {
            return false;
        }
        insertEvent(tenantId, event);
        return true;
    }

    private void insertEvent(TenantId tenantId, TicketEvent event) {
        jdbc.update("""
                insert into ticket_event(
                    tenant_id, event_id, ticket_id, event_type,
                    from_status, to_status, actor_user_id,
                    reason, payload_json, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tenantId.value(),
                event.eventId(),
                event.ticketId().value(),
                event.eventType(),
                event.fromStatus() == null ? null : event.fromStatus().name(),
                event.toStatus() == null ? null : event.toStatus().name(),
                event.actorUserId(),
                event.reason(),
                normalizeJson(event.payloadJson()),
                timestamp(event.createdAt()));
    }

    private Ticket normalizeTicket(Ticket ticket) {
        return new Ticket(
                ticket.ticketId(),
                ticket.customerId(),
                ticket.createdByUserId(),
                ticket.subject(),
                ticket.description(),
                ticket.status(),
                ticket.priority(),
                ticket.queueName(),
                ticket.assigneeUserId(),
                ticket.conversationId(),
                ticket.resolution(),
                normalizeJson(ticket.commercialRefsJson()),
                ticket.slaDueAt(),
                ticket.version(),
                ticket.createdAt(),
                ticket.updatedAt(),
                ticket.resolvedAt(),
                ticket.closedAt());
    }

    private TicketEvent normalizeEvent(TicketEvent event) {
        return new TicketEvent(
                event.eventId(),
                event.ticketId(),
                event.eventType(),
                event.fromStatus(),
                event.toStatus(),
                event.actorUserId(),
                event.reason(),
                normalizeJson(event.payloadJson()),
                event.createdAt());
    }

    private String normalizeJson(String value) {
        if (value == null) {
            return null;
        }
        try {
            JsonNode tree = objectMapper.readTree(value);
            return objectMapper.writeValueAsString(tree);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored ticket JSON is invalid", exception);
        }
    }

    private static void validateEvents(Ticket ticket, List<TicketEvent> events) {
        if (events.isEmpty()) {
            throw new IllegalArgumentException("ticket workflow state requires at least one event");
        }
        if (events.stream().anyMatch(event -> !event.ticketId().equals(ticket.ticketId()))) {
            throw new IllegalArgumentException("all events must belong to the same ticket");
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static final RowMapper<Ticket> TICKET_MAPPER = (result, row) -> new Ticket(
            new TicketId(result.getString("ticket_id")),
            result.getString("customer_id"),
            result.getString("created_by_user_id"),
            result.getString("subject"),
            result.getString("description"),
            TicketStatus.valueOf(result.getString("status")),
            TicketPriority.valueOf(result.getString("priority")),
            result.getString("queue_name"),
            result.getString("assignee_user_id"),
            result.getString("conversation_id"),
            result.getString("resolution"),
            result.getString("commercial_refs_json"),
            instant(result.getTimestamp("sla_due_at")),
            result.getLong("version"),
            instant(result.getTimestamp("created_at")),
            instant(result.getTimestamp("updated_at")),
            instant(result.getTimestamp("resolved_at")),
            instant(result.getTimestamp("closed_at")));

    private static final RowMapper<TicketEvent> EVENT_MAPPER = (result, row) ->
            new TicketEvent(
                    result.getString("event_id"),
                    new TicketId(result.getString("ticket_id")),
                    result.getString("event_type"),
                    enumOrNull(result.getString("from_status"), TicketStatus.class),
                    enumOrNull(result.getString("to_status"), TicketStatus.class),
                    result.getString("actor_user_id"),
                    result.getString("reason"),
                    result.getString("payload_json"),
                    instant(result.getTimestamp("created_at")));

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static <T extends Enum<T>> T enumOrNull(String value, Class<T> type) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
