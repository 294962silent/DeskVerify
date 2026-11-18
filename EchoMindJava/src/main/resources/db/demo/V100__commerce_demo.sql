insert into tenant(id, display_name, status, created_at) values
    ('acme', 'Acme Corporation', 'ACTIVE', '2025-01-01 00:00:00'),
    ('globex', 'Globex Corporation', 'ACTIVE', '2025-01-01 00:00:00'),
    ('initech', 'Initech', 'ACTIVE', '2025-01-01 00:00:00');

insert into app_user(id, display_name, status, created_at) values
    ('demo-acme-user', 'Acme Demo User', 'ACTIVE', '2025-01-01 00:00:00'),
    ('demo-globex-user', 'Globex Demo User', 'ACTIVE', '2025-01-01 00:00:00'),
    ('demo-initech-user', 'Initech Demo User', 'ACTIVE', '2025-01-01 00:00:00');

insert into tenant_membership(tenant_id, user_id, status, created_at) values
    ('acme', 'demo-acme-user', 'ACTIVE', '2025-01-01 00:00:00'),
    ('globex', 'demo-globex-user', 'ACTIVE', '2025-01-01 00:00:00'),
    ('initech', 'demo-initech-user', 'ACTIVE', '2025-01-01 00:00:00');

insert into role_binding(tenant_id, user_id, role_name) values
    ('acme', 'demo-acme-user', 'CUSTOMER'),
    ('globex', 'demo-globex-user', 'CUSTOMER'),
    ('initech', 'demo-initech-user', 'CUSTOMER');

insert into customer_account(
    tenant_id, customer_id, display_name, status, external_ref, created_at
) values
    ('acme', 'customer-001', 'Acme North America', 'ACTIVE', 'ACME-001', '2025-01-01 00:00:00'),
    ('acme', 'customer-002', 'Acme Europe', 'ACTIVE', 'ACME-002', '2025-01-01 00:00:00'),
    ('globex', 'customer-001', 'Globex North America', 'ACTIVE', 'GLOBEX-001', '2025-01-01 00:00:00'),
    ('globex', 'customer-002', 'Globex Asia Pacific', 'ACTIVE', 'GLOBEX-002', '2025-01-01 00:00:00'),
    ('initech', 'customer-001', 'Initech North America', 'ACTIVE', 'INITECH-001', '2025-01-01 00:00:00'),
    ('initech', 'customer-002', 'Initech EMEA', 'ACTIVE', 'INITECH-002', '2025-01-01 00:00:00');

insert into customer_membership(tenant_id, user_id, customer_id, permission) values
    ('acme', 'demo-acme-user', 'customer-001', 'OWNER'),
    ('acme', 'demo-acme-user', 'customer-002', 'OWNER'),
    ('globex', 'demo-globex-user', 'customer-001', 'OWNER'),
    ('globex', 'demo-globex-user', 'customer-002', 'OWNER'),
    ('initech', 'demo-initech-user', 'customer-001', 'OWNER'),
    ('initech', 'demo-initech-user', 'customer-002', 'OWNER');

insert into commerce_plan(
    tenant_id, plan_id, code, display_name, billing_interval,
    amount_minor, currency, status, created_at
) values
    ('acme', 'acme-plan-pro', 'PRO', 'Acme Pro', 'MONTH', 4900, 'USD', 'ACTIVE', '2025-01-01 00:00:00'),
    ('globex', 'globex-plan-team', 'TEAM', 'Globex Team', 'MONTH', 9900, 'USD', 'ACTIVE', '2025-01-01 00:00:00'),
    ('initech', 'initech-plan-starter', 'STARTER', 'Initech Starter', 'MONTH', 1900, 'USD', 'ACTIVE', '2025-01-01 00:00:00');

insert into plan_entitlement(tenant_id, plan_id, code, entitlement_value) values
    ('acme', 'acme-plan-pro', 'seats', '25'),
    ('acme', 'acme-plan-pro', 'sso', 'true'),
    ('acme', 'acme-plan-pro', 'support', 'priority'),
    ('globex', 'globex-plan-team', 'seats', '100'),
    ('globex', 'globex-plan-team', 'data_export', 'true'),
    ('globex', 'globex-plan-team', 'audit_log', 'true'),
    ('initech', 'initech-plan-starter', 'seats', '5'),
    ('initech', 'initech-plan-starter', 'support', 'email'),
    ('initech', 'initech-plan-starter', 'api_access', 'false');

insert into subscription(
    tenant_id, subscription_id, customer_id, plan_id, status,
    started_at, current_period_start, current_period_end, canceled_at
) values
    ('acme', 'acme-sub-001', 'customer-001', 'acme-plan-pro', 'ACTIVE',
        '2025-01-01 00:00:00', '2026-11-01 00:00:00', '2026-12-01 00:00:00', null),
    ('acme', 'acme-sub-002', 'customer-002', 'acme-plan-pro', 'ACTIVE',
        '2025-02-01 00:00:00', '2026-11-01 00:00:00', '2026-12-01 00:00:00', null),
    ('globex', 'globex-sub-001', 'customer-001', 'globex-plan-team', 'ACTIVE',
        '2025-01-01 00:00:00', '2026-11-01 00:00:00', '2026-12-01 00:00:00', null),
    ('globex', 'globex-sub-002', 'customer-002', 'globex-plan-team', 'ACTIVE',
        '2025-03-01 00:00:00', '2026-11-01 00:00:00', '2026-12-01 00:00:00', null),
    ('initech', 'initech-sub-001', 'customer-001', 'initech-plan-starter', 'ACTIVE',
        '2025-01-01 00:00:00', '2026-11-01 00:00:00', '2026-12-01 00:00:00', null),
    ('initech', 'initech-sub-002', 'customer-002', 'initech-plan-starter', 'ACTIVE',
        '2025-04-01 00:00:00', '2026-11-01 00:00:00', '2026-12-01 00:00:00', null);

insert into commerce_order(
    tenant_id, order_id, customer_id, product_code, quantity,
    amount_minor, currency, status, ordered_at, fulfilled_at, subscription_id
) values
    ('acme', 'acme-order-001', 'customer-001', 'PRO-MONTHLY', 1,
        4900, 'USD', 'PAID', '2026-10-01 00:00:00', '2026-10-01 00:05:00', 'acme-sub-001'),
    ('acme', 'acme-order-002', 'customer-001', 'PRO-SEAT', 3,
        3000, 'USD', 'PAID', '2026-10-15 00:00:00', '2026-10-15 00:05:00', 'acme-sub-001'),
    ('acme', 'acme-order-003', 'customer-002', 'PRO-MONTHLY', 1,
        4900, 'USD', 'PAID', '2026-10-02 00:00:00', '2026-10-02 00:05:00', 'acme-sub-002'),
    ('acme', 'acme-order-004', 'customer-002', 'PRO-SEAT', 5,
        5000, 'USD', 'PAID', '2026-10-16 00:00:00', '2026-10-16 00:05:00', 'acme-sub-002'),
    ('globex', 'globex-order-001', 'customer-001', 'TEAM-MONTHLY', 1,
        9900, 'USD', 'PAID', '2026-10-01 00:00:00', '2026-10-01 00:05:00', 'globex-sub-001'),
    ('globex', 'globex-order-002', 'customer-001', 'TEAM-SEAT', 10,
        20000, 'USD', 'PAID', '2026-10-15 00:00:00', '2026-10-15 00:05:00', 'globex-sub-001'),
    ('globex', 'globex-order-003', 'customer-002', 'TEAM-MONTHLY', 1,
        9900, 'USD', 'PAID', '2026-10-02 00:00:00', '2026-10-02 00:05:00', 'globex-sub-002'),
    ('globex', 'globex-order-004', 'customer-002', 'TEAM-SEAT', 8,
        16000, 'USD', 'PAID', '2026-10-16 00:00:00', '2026-10-16 00:05:00', 'globex-sub-002'),
    ('initech', 'initech-order-001', 'customer-001', 'STARTER-MONTHLY', 1,
        1900, 'USD', 'PAID', '2026-10-01 00:00:00', '2026-10-01 00:05:00', 'initech-sub-001'),
    ('initech', 'initech-order-002', 'customer-001', 'STARTER-SEAT', 2,
        1000, 'USD', 'PAID', '2026-10-15 00:00:00', '2026-10-15 00:05:00', 'initech-sub-001'),
    ('initech', 'initech-order-003', 'customer-002', 'STARTER-MONTHLY', 1,
        1900, 'USD', 'PAID', '2026-10-02 00:00:00', '2026-10-02 00:05:00', 'initech-sub-002'),
    ('initech', 'initech-order-004', 'customer-002', 'STARTER-SEAT', 4,
        2000, 'USD', 'PAID', '2026-10-16 00:00:00', '2026-10-16 00:05:00', 'initech-sub-002');

insert into invoice(
    tenant_id, invoice_id, customer_id, subscription_id,
    amount_minor, currency, status, issued_at, due_at, paid_at
) values
    ('acme', 'acme-invoice-001', 'customer-001', 'acme-sub-001',
        4900, 'USD', 'PAID', '2026-09-01 00:00:00', '2026-09-08 00:00:00', '2026-09-02 00:00:00'),
    ('acme', 'acme-invoice-002', 'customer-001', 'acme-sub-001',
        4900, 'USD', 'OVERDUE', '2026-10-01 00:00:00', '2026-10-08 00:00:00', null),
    ('acme', 'acme-invoice-003', 'customer-002', 'acme-sub-002',
        4900, 'USD', 'PAID', '2026-09-01 00:00:00', '2026-09-08 00:00:00', '2026-09-03 00:00:00'),
    ('acme', 'acme-invoice-004', 'customer-002', 'acme-sub-002',
        4900, 'USD', 'OVERDUE', '2026-10-01 00:00:00', '2026-10-08 00:00:00', null),
    ('globex', 'globex-invoice-001', 'customer-001', 'globex-sub-001',
        9900, 'USD', 'PAID', '2026-09-01 00:00:00', '2026-09-08 00:00:00', '2026-09-02 00:00:00'),
    ('globex', 'globex-invoice-002', 'customer-001', 'globex-sub-001',
        9900, 'USD', 'OVERDUE', '2026-10-01 00:00:00', '2026-10-08 00:00:00', null),
    ('globex', 'globex-invoice-003', 'customer-002', 'globex-sub-002',
        9900, 'USD', 'PAID', '2026-09-01 00:00:00', '2026-09-08 00:00:00', '2026-09-03 00:00:00'),
    ('globex', 'globex-invoice-004', 'customer-002', 'globex-sub-002',
        9900, 'USD', 'OVERDUE', '2026-10-01 00:00:00', '2026-10-08 00:00:00', null),
    ('initech', 'initech-invoice-001', 'customer-001', 'initech-sub-001',
        1900, 'USD', 'PAID', '2026-09-01 00:00:00', '2026-09-08 00:00:00', '2026-09-02 00:00:00'),
    ('initech', 'initech-invoice-002', 'customer-001', 'initech-sub-001',
        1900, 'USD', 'OVERDUE', '2026-10-01 00:00:00', '2026-10-08 00:00:00', null),
    ('initech', 'initech-invoice-003', 'customer-002', 'initech-sub-002',
        1900, 'USD', 'PAID', '2026-09-01 00:00:00', '2026-09-08 00:00:00', '2026-09-03 00:00:00'),
    ('initech', 'initech-invoice-004', 'customer-002', 'initech-sub-002',
        1900, 'USD', 'OVERDUE', '2026-10-01 00:00:00', '2026-10-08 00:00:00', null);

insert into payment_attempt(
    tenant_id, payment_attempt_id, customer_id, invoice_id,
    amount_minor, currency, status, attempted_at, failure_code
) values
    ('acme', 'acme-payment-001', 'customer-001', 'acme-invoice-002',
        4900, 'USD', 'FAILED', '2026-10-09 00:00:00', 'card_declined'),
    ('acme', 'acme-payment-002', 'customer-002', 'acme-invoice-004',
        4900, 'USD', 'FAILED', '2026-10-09 00:00:00', 'insufficient_funds'),
    ('globex', 'globex-payment-001', 'customer-001', 'globex-invoice-002',
        9900, 'USD', 'FAILED', '2026-10-09 00:00:00', 'card_declined'),
    ('globex', 'globex-payment-002', 'customer-002', 'globex-invoice-004',
        9900, 'USD', 'FAILED', '2026-10-09 00:00:00', 'insufficient_funds'),
    ('initech', 'initech-payment-001', 'customer-001', 'initech-invoice-002',
        1900, 'USD', 'FAILED', '2026-10-09 00:00:00', 'card_declined'),
    ('initech', 'initech-payment-002', 'customer-002', 'initech-invoice-004',
        1900, 'USD', 'FAILED', '2026-10-09 00:00:00', 'insufficient_funds');
