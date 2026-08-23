package com.paycore.risk;

import com.paycore.common.id.Ids;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Repository
public class BlocklistRepository {

    public record Entry(String id, String merchantId, String kind, String valueHash, String reason, Instant createdAt) {
    }

    private final JdbcClient jdbc;

    public BlocklistRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Merchant-scoped or global entry. */
    public boolean isBlocked(String merchantId, String kind, String valueHash) {
        return jdbc.sql("SELECT COUNT(*) FROM blocklist WHERE kind = :k AND value_hash = :v AND (merchant_id = :m OR merchant_id IS NULL)")
                .param("k", kind).param("v", valueHash).param("m", merchantId).query(Long.class).single() > 0;
    }

    public List<Entry> list(String merchantId) {
        return jdbc.sql("SELECT * FROM blocklist WHERE merchant_id = :m ORDER BY id DESC").param("m", merchantId)
                .query(Entry.class).list();
    }

    public Entry add(String merchantId, String kind, String valueHash, String reason, Instant now) {
        String id = Ids.newId("blk");
        jdbc.sql("""
                INSERT INTO blocklist (id, merchant_id, kind, value_hash, reason, created_at)
                VALUES (:id, :m, :k, :v, :r, :now)
                ON CONFLICT (COALESCE(merchant_id, ''), kind, value_hash) DO NOTHING
                """).param("id", id).param("m", merchantId).param("k", kind).param("v", valueHash).param("r", reason)
                .param("now", now.atOffset(ZoneOffset.UTC)).update();
        return jdbc.sql("SELECT * FROM blocklist WHERE merchant_id = :m AND kind = :k AND value_hash = :v")
                .param("m", merchantId).param("k", kind).param("v", valueHash).query(Entry.class).single();
    }

    public int remove(String merchantId, String id) {
        return jdbc.sql("DELETE FROM blocklist WHERE id = :id AND merchant_id = :m").param("id", id).param("m", merchantId).update();
    }

    /** Emails are stored hashed: the blocklist must not become a plaintext list of customers. */
    public static String hashEmail(String email) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(email.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return "eh_" + HexFormat.of().formatHex(d).substring(0, 40);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
