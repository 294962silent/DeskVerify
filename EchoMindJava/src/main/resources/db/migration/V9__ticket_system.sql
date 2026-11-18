create table ticket (
    tenant_id varchar(64) not null,
    ticket_id varchar(128) not null,
    customer_id varchar(128) not null,
    created_by_user_id varchar(128) not null,
    subject varchar(255) not null,
    description text not null,
    status varchar(32) not null,
    priority varchar(32) not null,
    queue_name varchar(80) null,
    assignee_user_id varchar(128) null,
    conversation_id varchar(128) null,
    resolution text null,
    commercial_refs_json json null,
    sla_due_at timestamp(6) null,
    version bigint not null default 0,
    created_at timestamp(6) not null default current_timestamp(6),
    updated_at timestamp(6) not null default current_timestamp(6)
        on update current_timestamp(6),
    resolved_at timestamp(6) null,
    closed_at timestamp(6) null,
    primary key (tenant_id, ticket_id),
    constraint fk_ticket_customer foreign key (tenant_id, customer_id)
        references customer_account(tenant_id, customer_id),
    constraint chk_ticket_status check (
        status in ('OPEN', 'QUEUED', 'CLAIMED', 'RESOLVED', 'CLOSED', 'REOPENED')
    ),
    constraint chk_ticket_priority check (
        priority in ('LOW', 'NORMAL', 'HIGH', 'URGENT')
    ),
    constraint chk_ticket_version check (version >= 0),
    index idx_ticket_status_created (tenant_id, status, created_at),
    index idx_ticket_assignee_status (tenant_id, assignee_user_id, status),
    index idx_ticket_customer_created (tenant_id, customer_id, created_at)
);

create table ticket_event (
    tenant_id varchar(64) not null,
    event_id varchar(128) not null,
    ticket_id varchar(128) not null,
    event_type varchar(64) not null,
    from_status varchar(32) null,
    to_status varchar(32) null,
    actor_user_id varchar(128) not null,
    reason text null,
    payload_json json null,
    created_at timestamp(6) not null default current_timestamp(6),
    primary key (tenant_id, event_id),
    constraint fk_ticket_event_ticket foreign key (tenant_id, ticket_id)
        references ticket(tenant_id, ticket_id),
    constraint chk_ticket_event_from_status check (
        from_status is null or
        from_status in ('OPEN', 'QUEUED', 'CLAIMED', 'RESOLVED', 'CLOSED', 'REOPENED')
    ),
    constraint chk_ticket_event_to_status check (
        to_status is null or
        to_status in ('OPEN', 'QUEUED', 'CLAIMED', 'RESOLVED', 'CLOSED', 'REOPENED')
    ),
    index idx_ticket_event_ticket_created (tenant_id, ticket_id, created_at)
);
