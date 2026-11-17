alter table inbox_event
    drop primary key,
    add primary key (tenant_id, consumer_name, event_id);

-- Inbox lookups must always include tenant_id so one tenant cannot observe
-- or suppress another tenant's event processing state.

alter table idempotency_record
    add column completed_at timestamp(6) null;
