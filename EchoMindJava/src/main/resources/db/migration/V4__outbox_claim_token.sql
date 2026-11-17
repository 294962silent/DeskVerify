alter table outbox_event
    add column claim_token varchar(64) null,
    add index idx_outbox_claim_owner (lease_owner, claim_token);
