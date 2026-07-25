package com.paycore.merchant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * A merchant account. Immutable record: Spring Data JDBC creates a fresh copy on update.
 * Fee schedule lives on the merchant so pricing is per-merchant data, not code.
 */
@Table("merchants")
public record Merchant(
        @Id String id,
        String name,
        String email,
        String passwordHash,
        int feeBps,
        long feeFixedMinor,
        boolean isDemo,
        @Version Integer version,
        Instant createdAt
) {
}
