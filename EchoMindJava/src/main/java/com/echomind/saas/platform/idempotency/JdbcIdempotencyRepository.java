package com.echomind.saas.platform.idempotency;

import com.echomind.saas.identity.domain.TenantId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIdempotencyRepository implements IdempotencyRepository {

    private final JdbcTemplate jdbc;

    public JdbcIdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> tryAcquire(
            TenantId tenantId, String key, String requestHash, Instant expiresAt) {
        String attemptToken = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    insert into idempotency_record(
                        tenant_id, idempotency_key, request_hash, state,
                        attempt_token, expires_at
                    ) values (?, ?, ?, 'IN_PROGRESS', ?, ?)
                    """,
                    tenantId.value(),
                    key,
                    requestHash,
                    attemptToken,
                    Timestamp.from(expiresAt));
            return Optional.of(attemptToken);
        } catch (DuplicateKeyException duplicate) {
            int reclaimed = jdbc.update("""
                    update idempotency_record
                    set request_hash = ?,
                        state = 'IN_PROGRESS',
                        response_json = null,
                        attempt_token = ?,
                        created_at = current_timestamp(6),
                        expires_at = ?
                    where tenant_id = ?
                      and idempotency_key = ?
                      and expires_at <= current_timestamp(6)
                    """,
                    requestHash,
                    attemptToken,
                    Timestamp.from(expiresAt),
                    tenantId.value(),
                    key);
            if (reclaimed == 1) {
                return Optional.of(attemptToken);
            }

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
            return Optional.empty();
        }
    }

    @Override
    public void complete(
            TenantId tenantId, String key, String attemptToken, String responseJson) {
        int updated = jdbc.update("""
                update idempotency_record
                set state = 'COMPLETED',
                    response_json = ?,
                    attempt_token = null
                where tenant_id = ?
                  and idempotency_key = ?
                  and state = 'IN_PROGRESS'
                  and attempt_token = ?
                  and expires_at > current_timestamp(6)
                """, responseJson, tenantId.value(), key, attemptToken);
        if (updated != 1) {
            throw new IdempotencyStateException(
                    "idempotency attempt token is no longer current");
        }
    }
}
