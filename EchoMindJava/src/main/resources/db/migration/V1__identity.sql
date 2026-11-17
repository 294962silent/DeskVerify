create table tenant (
    id varchar(64) primary key,
    display_name varchar(160) not null,
    status varchar(32) not null,
    created_at timestamp(6) not null default current_timestamp(6)
);

create table app_user (
    id varchar(128) primary key,
    display_name varchar(160) not null,
    status varchar(32) not null,
    created_at timestamp(6) not null default current_timestamp(6)
);

create table tenant_membership (
    tenant_id varchar(64) not null,
    user_id varchar(128) not null,
    status varchar(32) not null,
    created_at timestamp(6) not null default current_timestamp(6),
    primary key (tenant_id, user_id),
    constraint fk_membership_tenant foreign key (tenant_id) references tenant(id),
    constraint fk_membership_user foreign key (user_id) references app_user(id)
);

create table role_binding (
    tenant_id varchar(64) not null,
    user_id varchar(128) not null,
    role_name varchar(32) not null,
    primary key (tenant_id, user_id, role_name),
    constraint fk_role_membership foreign key (tenant_id, user_id)
        references tenant_membership(tenant_id, user_id)
);

create table customer_membership (
    tenant_id varchar(64) not null,
    user_id varchar(128) not null,
    customer_id varchar(128) not null,
    permission varchar(32) not null,
    primary key (tenant_id, user_id, customer_id),
    constraint fk_customer_membership foreign key (tenant_id, user_id)
        references tenant_membership(tenant_id, user_id)
);
