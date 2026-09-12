package com.paycore.webhooks;

import com.paycore.common.jdbc.Jsonb;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** SQL for the outbox and deliveries. The two SKIP LOCKED queries are the heart of the dispatcher. */
@Repository
public class WebhookRepository {

    private final JdbcClient jdbc;

    public WebhookRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // endpoints
    public List<WebhookEndpoint> endpointsForMerchant(String merchantId) {
        return jdbc.sql("SELECT * FROM webhook_endpoints WHERE merchant_id = :m ORDER BY id")
                .param("m", merchantId).query(this::endpoint).list();
    }

    public Optional<WebhookEndpoint> endpoint(String id, String merchantId) {
        return jdbc.sql("SELECT * FROM webhook_endpoints WHERE id = :id AND merchant_id = :m")
                .param("id", id).param("m", merchantId).query(this::endpoint).optional();
    }

    public Optional<WebhookEndpoint> endpointById(String id) {
        return jdbc.sql("SELECT * FROM webhook_endpoints WHERE id = :id").param("id", id).query(this::endpoint).optional();
    }

    public List<WebhookEndpoint> activeEndpoints(String merchantId) {
        return jdbc.sql("SELECT * FROM webhook_endpoints WHERE merchant_id = :m AND active ORDER BY id")
                .param("m", merchantId).query(this::endpoint).list();
    }

    public void insertEndpoint(WebhookEndpoint e) {
        jdbc.sql("""
                INSERT INTO webhook_endpoints (id, merchant_id, url, secret_enc, enabled_events, description, active, created_at, updated_at)
                VALUES (:id, :m, :url, :secret, :events, :desc, :active, :c, :u)
                """)
                .param("id", e.id()).param("m", e.merchantId()).param("url", e.url()).param("secret", e.secretEnc())
                .param("events", e.enabledEvents().toArray(new String[0])).param("desc", e.description())
                .param("active", e.active()).param("c", ts(e.createdAt())).param("u", ts(e.updatedAt()))
                .update();
    }

    public int deactivateEndpoint(String id, String merchantId, Instant now) {
        return jdbc.sql("UPDATE webhook_endpoints SET active = FALSE, updated_at = :now WHERE id = :id AND merchant_id = :m AND active")
                .param("now", ts(now)).param("id", id).param("m", merchantId).update();
    }

    private WebhookEndpoint endpoint(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Array arr = rs.getArray("enabled_events");
        List<String> events = arr == null ? List.of() : List.of((String[]) arr.getArray());
        return new WebhookEndpoint(rs.getString("id"), rs.getString("merchant_id"), rs.getString("url"),
                rs.getBytes("secret_enc"), events, rs.getString("description"), rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    // outbox
    public void insertEvent(OutboxEvent e) {
        jdbc.sql("""
                INSERT INTO outbox_events (id, merchant_id, type, aggregate_type, aggregate_id, payload, created_at)
                VALUES (:id, :m, :type, :at, :aid, :payload::jsonb, :c)
                """)
                .param("id", e.id()).param("m", e.merchantId()).param("type", e.type()).param("at", e.aggregateType())
                .param("aid", e.aggregateId()).param("payload", e.payload().json()).param("c", ts(e.createdAt()))
                .update();
    }

    /** Claim events that have no deliveries yet. SKIP LOCKED: concurrent dispatchers take disjoint sets. */
    public List<OutboxEvent> claimUnfannedEvents(int limit) {
        return jdbc.sql("SELECT * FROM outbox_events WHERE fanned_out_at IS NULL ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED")
                .param("limit", limit).query(this::event).list();
    }

    public void markFannedOut(String eventId, Instant now) {
        jdbc.sql("UPDATE outbox_events SET fanned_out_at = :now WHERE id = :id").param("now", ts(now)).param("id", eventId).update();
    }

    public Optional<OutboxEvent> event(String id, String merchantId) {
        return jdbc.sql("SELECT * FROM outbox_events WHERE id = :id AND merchant_id = :m")
                .param("id", id).param("m", merchantId).query(this::event).optional();
    }

    public Optional<OutboxEvent> eventById(String id) {
        return jdbc.sql("SELECT * FROM outbox_events WHERE id = :id").param("id", id).query(this::event).optional();
    }

    public List<OutboxEvent> eventsForMerchant(String merchantId, String type, String cursor, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM outbox_events WHERE merchant_id = :m");
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("m", merchantId);
        if (type != null && !type.isBlank()) {
            sql.append(" AND type = :type");
            params.put("type", type);
        }
        if (cursor != null && !cursor.isBlank()) {
            sql.append(" AND id < :cursor");
            params.put("cursor", cursor);
        }
        sql.append(" ORDER BY id DESC LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query(this::event).list();
    }

    private OutboxEvent event(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        java.sql.Timestamp fanned = rs.getTimestamp("fanned_out_at");
        return new OutboxEvent(rs.getString("id"), rs.getString("merchant_id"), rs.getString("type"),
                rs.getString("aggregate_type"), rs.getString("aggregate_id"), new Jsonb(rs.getString("payload")),
                rs.getTimestamp("created_at").toInstant(), fanned == null ? null : fanned.toInstant());
    }

    // deliveries
    public void insertDelivery(WebhookDelivery d) {
        jdbc.sql("""
                INSERT INTO webhook_deliveries (id, event_id, endpoint_id, merchant_id, status, attempts, next_attempt_at, created_at, updated_at)
                VALUES (:id, :e, :ep, :m, 'pending', 0, :next, :c, :u)
                ON CONFLICT (event_id, endpoint_id) DO NOTHING
                """)
                .param("id", d.id()).param("e", d.eventId()).param("ep", d.endpointId()).param("m", d.merchantId())
                .param("next", ts(d.nextAttemptAt())).param("c", ts(d.createdAt())).param("u", ts(d.updatedAt()))
                .update();
    }

    /** Claim due deliveries. */
    public List<WebhookDelivery> claimDue(Instant now, Duration lease, int limit) {
        List<WebhookDelivery> due = jdbc.sql("""
                SELECT * FROM webhook_deliveries
                 WHERE status = 'pending' AND next_attempt_at <= :now
                 ORDER BY next_attempt_at
                 LIMIT :limit FOR UPDATE SKIP LOCKED
                """).param("now", ts(now)).param("limit", limit).query(this::delivery).list();
        for (WebhookDelivery d : due) {
            jdbc.sql("UPDATE webhook_deliveries SET next_attempt_at = :lease, attempts = attempts + 1, updated_at = :now WHERE id = :id")
                    .param("lease", ts(now.plus(lease))).param("now", ts(now)).param("id", d.id()).update();
        }
        return due;
    }

    public void recordAttempt(WebhookDeliveryAttempt a) {
        jdbc.sql("""
                INSERT INTO webhook_delivery_attempts (id, delivery_id, attempt_no, status_code, error, duration_ms, response_snippet, created_at)
                VALUES (:id, :d, :n, :code, :err, :ms, :snip, :c)
                """)
                .param("id", a.id()).param("d", a.deliveryId()).param("n", a.attemptNo()).param("code", a.statusCode())
                .param("err", a.error()).param("ms", a.durationMs()).param("snip", a.responseSnippet()).param("c", ts(a.createdAt()))
                .update();
    }

    public void markDelivered(String id, int statusCode, Instant now) {
        jdbc.sql("UPDATE webhook_deliveries SET status = 'delivered', last_status_code = :code, last_error = NULL, delivered_at = :now, updated_at = :now WHERE id = :id")
                .param("code", statusCode).param("now", ts(now)).param("id", id).update();
    }

    public void scheduleRetry(String id, Integer statusCode, String error, Instant nextAttemptAt, Instant now) {
        jdbc.sql("UPDATE webhook_deliveries SET last_status_code = :code, last_error = :err, next_attempt_at = :next, updated_at = :now WHERE id = :id")
                .param("code", statusCode).param("err", error).param("next", ts(nextAttemptAt)).param("now", ts(now)).param("id", id).update();
    }

    public void markDead(String id, Integer statusCode, String error, Instant now) {
        jdbc.sql("UPDATE webhook_deliveries SET status = 'dead', last_status_code = :code, last_error = :err, updated_at = :now WHERE id = :id")
                .param("code", statusCode).param("err", error).param("now", ts(now)).param("id", id).update();
    }

    /** Manual replay from the dashboard: back to pending, due now, attempt counter preserved for the audit trail. */
    public int replay(String id, String merchantId, Instant now) {
        return jdbc.sql("UPDATE webhook_deliveries SET status = 'pending', next_attempt_at = :now, updated_at = :now WHERE id = :id AND merchant_id = :m AND status <> 'pending'")
                .param("now", ts(now)).param("id", id).param("m", merchantId).update();
    }

    public Optional<WebhookDelivery> delivery(String id, String merchantId) {
        return jdbc.sql("SELECT * FROM webhook_deliveries WHERE id = :id AND merchant_id = :m")
                .param("id", id).param("m", merchantId).query(this::delivery).optional();
    }

    public List<WebhookDelivery> deliveriesForEvent(String eventId, String merchantId) {
        return jdbc.sql("SELECT * FROM webhook_deliveries WHERE event_id = :e AND merchant_id = :m ORDER BY id")
                .param("e", eventId).param("m", merchantId).query(this::delivery).list();
    }

    public List<WebhookDelivery> deliveriesForMerchant(String merchantId, String status, String endpointId, String cursor, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM webhook_deliveries WHERE merchant_id = :m");
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("m", merchantId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.put("status", status);
        }
        if (endpointId != null && !endpointId.isBlank()) {
            sql.append(" AND endpoint_id = :ep");
            params.put("ep", endpointId);
        }
        if (cursor != null && !cursor.isBlank()) {
            sql.append(" AND id < :cursor");
            params.put("cursor", cursor);
        }
        sql.append(" ORDER BY id DESC LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query(this::delivery).list();
    }

    public List<WebhookDeliveryAttempt> attempts(String deliveryId) {
        return jdbc.sql("SELECT * FROM webhook_delivery_attempts WHERE delivery_id = :d ORDER BY attempt_no")
                .param("d", deliveryId).query(this::attempt).list();
    }

    public long countByStatus(String merchantId, String status) {
        return jdbc.sql("SELECT COUNT(*) FROM webhook_deliveries WHERE merchant_id = :m AND status = :s")
                .param("m", merchantId).param("s", status).query(Long.class).single();
    }

    private WebhookDelivery delivery(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        java.sql.Timestamp delivered = rs.getTimestamp("delivered_at");
        return new WebhookDelivery(rs.getString("id"), rs.getString("event_id"), rs.getString("endpoint_id"),
                rs.getString("merchant_id"), rs.getString("status"), rs.getInt("attempts"),
                rs.getTimestamp("next_attempt_at").toInstant(), (Integer) rs.getObject("last_status_code"),
                rs.getString("last_error"), delivered == null ? null : delivered.toInstant(),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private WebhookDeliveryAttempt attempt(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new WebhookDeliveryAttempt(rs.getString("id"), rs.getString("delivery_id"), rs.getInt("attempt_no"),
                (Integer) rs.getObject("status_code"), rs.getString("error"), (Integer) rs.getObject("duration_ms"),
                rs.getString("response_snippet"), rs.getTimestamp("created_at").toInstant());
    }

    private static java.time.OffsetDateTime ts(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
