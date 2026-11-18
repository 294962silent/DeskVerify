alter table subscription
    add unique key uk_subscription_owner (
        tenant_id, customer_id, subscription_id
    );

alter table invoice
    add unique key uk_invoice_owner (
        tenant_id, customer_id, invoice_id
    );

alter table commerce_order
    drop foreign key fk_order_subscription,
    add index idx_order_subscription_owner (
        tenant_id, customer_id, subscription_id
    ),
    add constraint fk_order_subscription_owner
        foreign key (tenant_id, customer_id, subscription_id)
        references subscription(tenant_id, customer_id, subscription_id);

alter table invoice
    drop foreign key fk_invoice_subscription,
    add index idx_invoice_subscription_owner (
        tenant_id, customer_id, subscription_id
    ),
    add constraint fk_invoice_subscription_owner
        foreign key (tenant_id, customer_id, subscription_id)
        references subscription(tenant_id, customer_id, subscription_id);

alter table payment_attempt
    drop foreign key fk_payment_attempt_invoice,
    add index idx_payment_attempt_invoice_owner (
        tenant_id, customer_id, invoice_id
    ),
    add constraint fk_payment_attempt_invoice_owner
        foreign key (tenant_id, customer_id, invoice_id)
        references invoice(tenant_id, customer_id, invoice_id);
