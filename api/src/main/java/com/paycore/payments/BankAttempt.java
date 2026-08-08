package com.paycore.payments;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/** The gateway's record of one call to the bank. Created BEFORE the call so the reference survives a crash. */
@Table("bank_attempts")
public record BankAttempt(
        @Id String id,
        String paymentId,
        String refundId,
        String kind,
        String bankRef,
        long amountMinor,
        String currency,
        String outcome,
        String declineCode,
        Integer latencyMs,
        Instant createdAt,
        Instant resolvedAt,
        String resolution
) {
    public static final String KIND_AUTHORIZE = "authorize";
    public static final String KIND_REFUND = "refund";
    public static final String IN_FLIGHT = "in_flight";
    public static final String APPROVED = "approved";
    public static final String DECLINED = "declined";
    public static final String TIMEOUT = "timeout";
    public static final String ERROR = "error";
}
