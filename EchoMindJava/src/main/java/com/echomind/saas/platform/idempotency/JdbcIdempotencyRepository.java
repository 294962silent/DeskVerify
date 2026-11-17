package com.echomind.saas.platform.idempotency;

import com.echomind.saas.identity.domain.TenantId;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public final class JdbcIdempotencyRepository implements IdempotencyRepository {

    private final JdbcTemplate jdbc;

    public JdbcIdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryAcquire(
            TenantId tenantId, String key, String requestHash, Instant expiresAt) {
        try {
            jdbc.update("""
                    insert into idempotency_record(
                        tenant_id, idempotency_key, request_hash, state, expires_at
                    ) values (?, ?, ?, 'IN_PROGRESS', ?)
                    """, tenantId.value(), key, requestHash, expiresAt);
            return true;
        } catch (DuplicateKeyException duplicate) {
            List<String> hashes = jdbc.queryForList("""
                    select request_hash from idempotency_record
                    where tenant_id = ? and idempotency_key = ?
                    """, String.class, tenantId.value(), key);
            if (hashes.isEmpty()) {
                throw duplicate;
            }
            if (!hashes.getFirst().equals(requestHash)) {
                throw new IdempotencyConflictException(
                        "idempotency key was reused with a different request");
            }
            return false;
        }
    }

    @Override
    public void complete(TenantId tenantId, String key, String responseJson) {
        jdbc.update("""
                update idempotency_record
                set state = 'COMPLETED', response_json = ?
                where tenant_id = ? and idempotency_key = ?
                """, responseJson, tenantId.value(), key);
    }
}
