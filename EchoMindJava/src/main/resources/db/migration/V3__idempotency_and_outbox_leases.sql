alter table idempotency_record
    add column attempt_token varchar(64) null;

alter table outbox_event
    add column lease_owner varchar(64) null,
    add column lease_until timestamp(6) null,
    add column last_error varchar(1000) null,
    add index idx_outbox_claim (state, available_at, lease_until);
