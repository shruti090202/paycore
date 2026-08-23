package com.paycore.risk;

import com.paycore.common.id.Ids;
import com.paycore.common.jdbc.Jsonb;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class RiskDecisionRepository {

    public record Row(String id, String paymentId, String merchantId, int score, String decision, Jsonb reasons,
                      Instant evaluatedAt) {
    }

    private final JdbcClient jdbc;

    public RiskDecisionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String paymentId, String merchantId, RiskDecision d, Instant now) {
        jdbc.sql("""
                INSERT INTO risk_decisions (id, payment_id, merchant_id, score, decision, reasons, evaluated_at)
                VALUES (:id, :p, :m, :score, :decision, :reasons::jsonb, :now)
                ON CONFLICT (payment_id) DO NOTHING
                """)
                .param("id", Ids.newId("rsk")).param("p", paymentId).param("m", merchantId).param("score", d.score())
                .param("decision", d.outcome().wire()).param("reasons", Jsonb.of(d.reasons()).json())
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
    }

    public Optional<Row> forPayment(String paymentId, String merchantId) {
        return jdbc.sql("SELECT * FROM risk_decisions WHERE payment_id = :p AND merchant_id = :m")
                .param("p", paymentId).param("m", merchantId).query(this::map).optional();
    }

    public List<Row> list(String merchantId, String decision, String cursor, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM risk_decisions WHERE merchant_id = :m");
        Map<String, Object> params = new HashMap<>();
        params.put("m", merchantId);
        if (decision != null && !decision.isBlank()) {
            sql.append(" AND decision = :d");
            params.put("d", decision);
        }
        if (cursor != null && !cursor.isBlank()) {
            sql.append(" AND id < :cursor");
            params.put("cursor", cursor);
        }
        sql.append(" ORDER BY id DESC LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query(this::map).list();
    }

    private Row map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Row(rs.getString("id"), rs.getString("payment_id"), rs.getString("merchant_id"), rs.getInt("score"),
                rs.getString("decision"), new Jsonb(rs.getString("reasons")), rs.getTimestamp("evaluated_at").toInstant());
    }
}
