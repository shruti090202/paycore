package com.paycore.jobs;

import com.paycore.idempotency.IdempotencyStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps the free-tier database (0.5 GB) from filling with operational rows. Money rows (payments, refunds,
 * ledger) are never touched. Batched deletes so a long-neglected table cannot lock everything for minutes.
 */
@Component
public class RetentionCleanupJob implements Job {

    public static final String NAME = "retention-cleanup";
    private static final int BATCH = 5_000;

    private final IdempotencyStore idempotency;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RetentionCleanupJob(IdempotencyStore idempotency, JdbcClient jdbc, PlatformTransactionManager txManager, Clock clock) {
        this.idempotency = idempotency;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Map<String, Object> run() {
        Instant now = clock.instant();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("idempotency_keys", idempotency.deleteExpired(now, BATCH));
        out.put("job_runs", delete("DELETE FROM job_runs WHERE id IN (SELECT id FROM job_runs WHERE status <> 'running' AND started_at < :t LIMIT :n)",
                now.minus(Duration.ofDays(7))));
        out.put("webhook_delivery_attempts", delete("""
                DELETE FROM webhook_delivery_attempts WHERE id IN (
                    SELECT a.id FROM webhook_delivery_attempts a JOIN webhook_deliveries d ON d.id = a.delivery_id
                     WHERE d.status <> 'pending' AND d.updated_at < :t LIMIT :n)
                """, now.minus(Duration.ofDays(30))));
        out.put("webhook_deliveries", delete("""
                DELETE FROM webhook_deliveries WHERE id IN (
                    SELECT d.id FROM webhook_deliveries d
                     WHERE d.status <> 'pending' AND d.updated_at < :t
                       AND NOT EXISTS (SELECT 1 FROM webhook_delivery_attempts a WHERE a.delivery_id = d.id) LIMIT :n)
                """, now.minus(Duration.ofDays(30))));
        out.put("outbox_events", delete("""
                DELETE FROM outbox_events WHERE id IN (
                    SELECT e.id FROM outbox_events e
                     WHERE e.fanned_out_at IS NOT NULL AND e.created_at < :t
                       AND NOT EXISTS (SELECT 1 FROM webhook_deliveries d WHERE d.event_id = e.id) LIMIT :n)
                """, now.minus(Duration.ofDays(30))));
        return out;
    }

    private int delete(String sql, Instant before) {
        return tx.execute(s -> jdbc.sql(sql).param("t", before.atOffset(ZoneOffset.UTC)).param("n", BATCH).update());
    }
}
