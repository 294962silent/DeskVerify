update idempotency_record
set completed_at = created_at
where state = 'COMPLETED'
  and completed_at is null;
