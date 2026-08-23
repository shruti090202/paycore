package com.paycore.risk;

import com.paycore.common.id.Ids;
import com.paycore.common.jdbc.Jsonb;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class RiskRuleRepository {

    private final JdbcClient jdbc;

    public RiskRuleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Global defaults overlaid with the merchant's overrides, keyed by type, in global order. */
    public List<RuleConfig> effectiveRules(String merchantId) {
        List<RuleConfig> rows = jdbc.sql("""
                SELECT * FROM risk_rules WHERE merchant_id IS NULL OR merchant_id = :m
                ORDER BY (merchant_id IS NOT NULL), id
                """).param("m", merchantId).query(this::map).list();
        Map<String, RuleConfig> byType = new LinkedHashMap<>();
        for (RuleConfig r : rows) {
            byType.put(r.type(), r); // override rows come last and replace the global of the same type
        }
        return List.copyOf(byType.values());
    }

    public void upsertOverride(String merchantId, String type, String name, Map<String, Object> params, int weight,
                              boolean enabled, Instant now) {
        jdbc.sql("""
                INSERT INTO risk_rules (id, merchant_id, type, name, params, weight, enabled, created_at, updated_at)
                VALUES (:id, :m, :type, :name, :params::jsonb, :weight, :enabled, :now, :now)
                ON CONFLICT (COALESCE(merchant_id, ''), type) DO UPDATE
                    SET name = EXCLUDED.name, params = EXCLUDED.params, weight = EXCLUDED.weight,
                        enabled = EXCLUDED.enabled, updated_at = EXCLUDED.updated_at
                """)
                .param("id", Ids.newId("rule")).param("m", merchantId).param("type", type).param("name", name)
                .param("params", Jsonb.of(params == null ? Map.of() : params).json()).param("weight", weight)
                .param("enabled", enabled).param("now", now.atOffset(ZoneOffset.UTC))
                .update();
    }

    public int deleteOverride(String merchantId, String type) {
        return jdbc.sql("DELETE FROM risk_rules WHERE merchant_id = :m AND type = :t").param("m", merchantId).param("t", type).update();
    }

    private RuleConfig map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new RuleConfig(rs.getString("id"), rs.getString("merchant_id"), rs.getString("type"), rs.getString("name"),
                new Jsonb(rs.getString("params")).asMap(), rs.getInt("weight"), rs.getBoolean("enabled"));
    }
}
