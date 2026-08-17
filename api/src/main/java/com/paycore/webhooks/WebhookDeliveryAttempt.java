package com.paycore.webhooks;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("webhook_delivery_attempts")
public record WebhookDeliveryAttempt(
        @Id String id,
        String deliveryId,
        int attemptNo,
        Integer statusCode,
        String error,
        Integer durationMs,
        String responseSnippet,
        Instant createdAt
) {
}
