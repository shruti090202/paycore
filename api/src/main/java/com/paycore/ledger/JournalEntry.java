package com.paycore.ledger;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/** One business event in the ledger (a capture, a refund, a payout). Its postings must sum to zero. */
@Table("journal_entries")
public record JournalEntry(
        @Id String id,
        String kind,
        String referenceType,
        String referenceId,
        String currency,
        String description,
        Instant createdAt
) {
}
