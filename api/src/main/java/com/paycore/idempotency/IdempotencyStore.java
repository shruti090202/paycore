package com.paycore.idempotency;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Persistence for idempotency keys. Every method runs in its own short transaction (REQUIRES_NEW) because the
 * filter that uses it sits outside any controller transaction, and the claim must be visible to other
 * requests immediately.
 */
@Repository
public class IdempotencyStore {

    public static final Duration TTL = Duration.ofHours(24);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public IdempotencyStore(JdbcClient jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    /** Outcome of trying to claim a key. */
    public sealed interface Claim permits Claimed, Existing {
    }

    /** We own the key; the request must run. */
    public record Claimed() implements Claim {
    }

    /** Someone claimed it before us: replay, conflict, or wait, depending on the record. */
    public record Existing(IdempotencyRecord record) implements Claim {
    }

    public Claim claim(String merchantId, String key, String requestHash, String method, String path, Instant now) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                tx.executeWithoutResult(s -> jdbc.sql("""
                        INSERT INTO idempotency_keys (merchant_id, idem_key, request_hash, method, path, status, created_at, expires_at)
                        VALUES (:m, :k, :h, :method, :path, 'in_progress', :now, :exp)
                        """)
                        .param("m", merchantId).param("k", key).param("h", requestHash)
                        .param("method", method).param("path", path)
                        .param("now", now.atOffset(ZoneOffset.UTC)).param("exp", now.plus(TTL).atOffset(ZoneOffset.UTC))
                        .update());
                return new Claimed();
            } catch (DuplicateKeyException e) {
                Optional<IdempotencyRecord> existing = find(merchantId, key);
                if (existing.isEmpty()) {
                    continue; // deleted between our insert and select: try once more
                }
                if (existing.get().expiresAt().isBefore(now)) {
                    delete(merchantId, key); // expired: forget it and claim afresh
                    continue;
                }
                return new Existing(existing.get());
            }
        }
        // Two failed rounds means a pathological race; treat as in-flight so the client retries.
        return find(merchantId, key).<Claim>map(Existing::new).orElseGet(Claimed::new);
    }

    public Optional<IdempotencyRecord> find(String merchantId, String key) {
        return jdbc.sql("SELECT * FROM idempotency_keys WHERE merchant_id = :m AND idem_key = :k")
                .param("m", merchantId).param("k", key)
                .query(IdempotencyRecord.class).optional();
    }

    public void complete(String merchantId, String key, int status, String contentType, String body, Instant now) {
        tx.executeWithoutResult(s -> jdbc.sql("""
                UPDATE idempotency_keys
                   SET status = 'completed', response_status = :status, response_content_type = :ct,
                       response_body = :body, completed_at = :now
                 WHERE merchant_id = :m AND idem_key = :k AND status = 'in_progress'
                """)
                .param("status", status).param("ct", contentType).param("body", body)
                .param("now", now.atOffset(ZoneOffset.UTC)).param("m", merchantId).param("k", key)
                .update());
    }

    /** Release a claim whose request failed on our side (5xx / exception) so the client can retry. */
    public void delete(String merchantId, String key) {
        tx.executeWithoutResult(s -> jdbc.sql("DELETE FROM idempotency_keys WHERE merchant_id = :m AND idem_key = :k")
                .param("m", merchantId).param("k", key).update());
    }

    public int deleteExpired(Instant now, int limit) {
        return tx.execute(s -> jdbc.sql("""
                DELETE FROM idempotency_keys WHERE (merchant_id, idem_key) IN (
                    SELECT merchant_id, idem_key FROM idempotency_keys WHERE expires_at < :now LIMIT :limit)
                """).param("now", now.atOffset(ZoneOffset.UTC)).param("limit", limit).update());
    }
}
