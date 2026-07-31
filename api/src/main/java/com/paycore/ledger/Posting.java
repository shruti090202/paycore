package com.paycore.ledger;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/** One leg of a journal entry. Amounts are always positive; the direction carries the sign. */
@Table("postings")
public record Posting(
        @Id String id,
        String journalEntryId,
        String accountId,
        String direction,
        long amountMinor,
        String currency,
        Instant createdAt
) {
    public long signedMinor() {
        return "debit".equals(direction) ? amountMinor : -amountMinor;
    }
}
