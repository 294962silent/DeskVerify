create table customer_account (
    tenant_id varchar(64) not null,
    customer_id varchar(128) not null,
    display_name varchar(160) not null,
    status varchar(32) not null,
    external_ref varchar(160) null,
    created_at timestamp(6) not null default current_timestamp(6),
    primary key (tenant_id, customer_id)
);

create table commerce_plan (
    tenant_id varchar(64) not null,
    plan_id varchar(128) not null,
    code varchar(80) not null,
    display_name varchar(160) not null,
    billing_interval varchar(32) not null,
    amount_minor bigint not null,
    currency char(3) not null,
    status varchar(32) not null,
    created_at timestamp(6) not null default current_timestamp(6),
    primary key (tenant_id, plan_id),
    unique key uk_commerce_plan_code (tenant_id, code)
);

create table subscription (
    tenant_id varchar(64) not null,
    subscription_id varchar(128) not null,
    customer_id varchar(128) not null,
    plan_id varchar(128) not null,
    status varchar(32) not null,
    started_at timestamp(6) not null,
    current_period_start timestamp(6) not null,
    current_period_end timestamp(6) not null,
    canceled_at timestamp(6) null,
    primary key (tenant_id, subscription_id),
    constraint fk_subscription_account foreign key (tenant_id, customer_id)
        references customer_account(tenant_id, customer_id),
    constraint fk_subscription_plan foreign key (tenant_id, plan_id)
        references commerce_plan(tenant_id, plan_id),
    index idx_subscription_customer (tenant_id, customer_id, status, started_at)
);

create table plan_entitlement (
    tenant_id varchar(64) not null,
    plan_id varchar(128) not null,
    code varchar(80) not null,
    entitlement_value varchar(255) not null,
    primary key (tenant_id, plan_id, code),
    constraint fk_entitlement_plan foreign key (tenant_id, plan_id)
        references commerce_plan(tenant_id, plan_id)
);

create table commerce_order (
    tenant_id varchar(64) not null,
    order_id varchar(128) not null,
    customer_id varchar(128) not null,
    product_code varchar(80) not null,
    quantity int not null,
    amount_minor bigint not null,
    currency char(3) not null,
    status varchar(32) not null,
    ordered_at timestamp(6) not null,
    fulfilled_at timestamp(6) null,
    subscription_id varchar(128) null,
    primary key (tenant_id, order_id),
    constraint fk_order_account foreign key (tenant_id, customer_id)
        references customer_account(tenant_id, customer_id),
    constraint fk_order_subscription foreign key (tenant_id, subscription_id)
        references subscription(tenant_id, subscription_id),
    index idx_order_customer (tenant_id, customer_id, ordered_at)
);

create table invoice (
    tenant_id varchar(64) not null,
    invoice_id varchar(128) not null,
    customer_id varchar(128) not null,
    subscription_id varchar(128) null,
    amount_minor bigint not null,
    currency char(3) not null,
    status varchar(32) not null,
    issued_at timestamp(6) not null,
    due_at timestamp(6) not null,
    paid_at timestamp(6) null,
    primary key (tenant_id, invoice_id),
    constraint fk_invoice_account foreign key (tenant_id, customer_id)
        references customer_account(tenant_id, customer_id),
    constraint fk_invoice_subscription foreign key (tenant_id, subscription_id)
        references subscription(tenant_id, subscription_id),
    index idx_invoice_customer (tenant_id, customer_id, due_at)
);

create table payment_attempt (
    tenant_id varchar(64) not null,
    payment_attempt_id varchar(128) not null,
    customer_id varchar(128) not null,
    invoice_id varchar(128) not null,
    amount_minor bigint not null,
    currency char(3) not null,
    status varchar(32) not null,
    attempted_at timestamp(6) not null,
    failure_code varchar(64) null,
    primary key (tenant_id, payment_attempt_id),
    constraint fk_payment_attempt_account foreign key (tenant_id, customer_id)
        references customer_account(tenant_id, customer_id),
    constraint fk_payment_attempt_invoice foreign key (tenant_id, invoice_id)
        references invoice(tenant_id, invoice_id),
    index idx_payment_attempt_customer (tenant_id, customer_id, attempted_at)
);
