create table idempotency_record (
    tenant_id varchar(64) not null,
    idempotency_key varchar(160) not null,
    request_hash char(64) not null,
    state varchar(32) not null,
    response_json json null,
    created_at timestamp(6) not null default current_timestamp(6),
    expires_at timestamp(6) not null,
    primary key (tenant_id, idempotency_key)
);

create table outbox_event (
    id char(36) primary key,
    tenant_id varchar(64) not null,
    aggregate_type varchar(80) not null,
    aggregate_id varchar(128) not null,
    event_type varchar(128) not null,
    payload_json json not null,
    state varchar(32) not null,
    attempts int not null default 0,
    available_at timestamp(6) not null default current_timestamp(6),
    created_at timestamp(6) not null default current_timestamp(6),
    published_at timestamp(6) null,
    index idx_outbox_pending (state, available_at)
);

create table inbox_event (
    consumer_name varchar(128) not null,
    event_id varchar(128) not null,
    tenant_id varchar(64) not null,
    received_at timestamp(6) not null default current_timestamp(6),
    primary key (consumer_name, event_id)
);
