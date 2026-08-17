package com.paycore.webhooks;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("webhook_deliveries")
public record WebhookDelivery(
        @Id String id,
        String eventId,
        String endpointId,
        String merchantId,
        String status,
        int attempts,
        Instant nextAttemptAt,
        Integer lastStatusCode,
        String lastError,
        Instant deliveredAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static final String PENDING = "pending";
    public static final String DELIVERED = "delivered";
    public static final String DEAD = "dead";
}
