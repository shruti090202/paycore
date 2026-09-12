package com.paycore.merchant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/** A merchant account. */
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
