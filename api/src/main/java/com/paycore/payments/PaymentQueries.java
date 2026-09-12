package com.paycore.payments;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** List queries with keyset (cursor) pagination. */
@Repository
public class PaymentQueries {

    private final JdbcClient jdbc;
    private final PaymentRepository payments;

    public PaymentQueries(JdbcClient jdbc, PaymentRepository payments) {
        this.jdbc = jdbc;
        this.payments = payments;
    }

    public record Filter(String merchantId, PaymentStatus status, Instant createdAfter, Instant createdBefore,
                         String customerEmail, String cursor, int limit) {
    }

    public record Page(List<Payment> items, String nextCursor) {
    }

    public Page list(Filter f) {
        StringBuilder sql = new StringBuilder("SELECT id FROM payments WHERE merchant_id = :merchantId");
        Map<String, Object> params = new HashMap<>();
        params.put("merchantId", f.merchantId());
        if (f.status() != null) {
            sql.append(" AND status = :status");
            params.put("status", f.status().wire());
        }
        if (f.createdAfter() != null) {
            sql.append(" AND created_at >= :after");
            params.put("after", f.createdAfter().atOffset(java.time.ZoneOffset.UTC));
        }
        if (f.createdBefore() != null) {
            sql.append(" AND created_at < :before");
            params.put("before", f.createdBefore().atOffset(java.time.ZoneOffset.UTC));
        }
        if (f.customerEmail() != null && !f.customerEmail().isBlank()) {
            sql.append(" AND customer_email = :email");
            params.put("email", f.customerEmail().toLowerCase());
        }
        if (f.cursor() != null && !f.cursor().isBlank()) {
            sql.append(" AND id < :cursor");
            params.put("cursor", f.cursor());
        }
        int limit = Math.max(1, Math.min(f.limit(), 100));
        sql.append(" ORDER BY id DESC LIMIT :limit");
        params.put("limit", limit + 1); // fetch one extra to know whether a next page exists

        // Keyset query returns ids; entities load through the repository so Payment has ONE mapping path (jsonb etc).
        List<String> ids = jdbc.sql(sql.toString()).params(params).query(String.class).list();
        String next = null;
        if (ids.size() > limit) {
            ids = ids.subList(0, limit);
            next = ids.get(ids.size() - 1);
        }
        Map<String, Payment> byId = new HashMap<>();
        payments.findAllById(ids).forEach(p -> byId.put(p.getId(), p));
        List<Payment> rows = ids.stream().map(byId::get).filter(Objects::nonNull).toList();
        return new Page(rows, next);
    }
}
