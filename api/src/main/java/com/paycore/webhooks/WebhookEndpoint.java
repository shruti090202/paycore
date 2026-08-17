package com.paycore.webhooks;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.List;

@Table("webhook_endpoints")
public record WebhookEndpoint(
        @Id String id,
        String merchantId,
        String url,
        byte[] secretEnc,
        List<String> enabledEvents,
        String description,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
    /** Empty list = subscribed to everything. */
    public boolean wants(String eventType) {
        return enabledEvents == null || enabledEvents.isEmpty() || enabledEvents.contains(eventType);
    }
}
