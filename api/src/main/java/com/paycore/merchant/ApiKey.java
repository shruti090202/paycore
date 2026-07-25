package com.paycore.merchant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/** A merchant secret key. Only the SHA-256 hash is persisted; {@code prefix} is for display ("sk_test_ab12"). */
@Table("api_keys")
public record ApiKey(
        @Id String id,
        String merchantId,
        String name,
        String prefix,
        String keyHash,
        Instant createdAt,
        Instant lastUsedAt,
        Instant revokedAt
) {
    public boolean revoked() {
        return revokedAt != null;
    }
}
