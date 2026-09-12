package com.paycore.payments;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/** A refund request. */
@Table("refunds")
public record Refund(
        @Id String id,
        String paymentId,
        String merchantId,
        long amountMinor,
        String currency,
        String status,
        String reason,
        String bankRef,
        String failureCode,
        Instant createdAt,
        Instant updatedAt
) {
    public static final String PENDING = "pending";
    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";

    public Refund withStatus(String newStatus, String newFailureCode, Instant now) {
        return new Refund(id, paymentId, merchantId, amountMinor, currency, newStatus, reason, bankRef, newFailureCode,
                createdAt, now);
    }
}
