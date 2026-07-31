package com.paycore.ledger;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("ledger_accounts")
public record LedgerAccount(
        @Id String id,
        String code,
        String type,
        String currency,
        String merchantId,
        Instant createdAt
) {
    public AccountType accountType() {
        return AccountType.fromWire(type);
    }
}
