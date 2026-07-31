package com.paycore.payments;

import com.paycore.common.jdbc.Jsonb;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/** Append-only timeline row: what happened to a payment, when, and the status edge it caused (if any). */
@Table("payment_events")
public record PaymentEvent(
        @Id String id,
        String paymentId,
        String type,
        String fromStatus,
        String toStatus,
        Jsonb data,
        Instant createdAt
) {
}
