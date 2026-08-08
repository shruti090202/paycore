package com.paycore.banksim;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.time.LocalDate;

/** A row in the simulated bank's own ledger of what it processed. */
@Table("banksim_transactions")
public record BankSimTransaction(
        @Id String bankRef,
        String kind,
        String parentRef,
        long amountMinor,
        String currency,
        String cardFingerprint,
        String cardLast4,
        String outcome,
        String declineCode,
        String authCode,
        Instant createdAt,
        LocalDate settledOn
) {
}
