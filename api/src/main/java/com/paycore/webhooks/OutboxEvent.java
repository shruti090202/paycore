package com.paycore.webhooks;

import com.paycore.common.jdbc.Jsonb;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/** A domain event, written inside the transaction that produced it. The row IS the merchant-visible event. */
@Table("outbox_events")
public record OutboxEvent(
        @Id String id,
        String merchantId,
        String type,
        String aggregateType,
        String aggregateId,
        Jsonb payload,
        Instant createdAt,
        Instant fannedOutAt
) {
}
