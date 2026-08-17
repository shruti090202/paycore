package com.paycore.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.paycore.webhooks.OutboxEvent;
import com.paycore.webhooks.WebhookDelivery;
import com.paycore.webhooks.WebhookDeliveryAttempt;
import com.paycore.webhooks.WebhookEndpoint;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class WebhookDtos {

    private WebhookDtos() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EndpointDto(String id, String object, String url, List<String> enabledEvents, String description,
                              boolean active, String secret, Instant createdAt) {
        public static EndpointDto from(WebhookEndpoint e, String secretOnce) {
            return new EndpointDto(e.id(), "webhook_endpoint", e.url(), e.enabledEvents(), e.description(), e.active(),
                    secretOnce, e.createdAt());
        }
    }

    public record EventDto(String id, String object, String type, Instant created, boolean livemode, Map<String, Object> data) {
        public static EventDto from(OutboxEvent e) {
            Map<String, Object> body = e.payload().asMap();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) body.getOrDefault("data", Map.of());
            return new EventDto(e.id(), "event", e.type(), e.createdAt(), false, data);
        }
    }

    public record AttemptDto(int attemptNo, Integer statusCode, String error, Integer durationMs, String responseSnippet,
                             Instant createdAt) {
        public static AttemptDto from(WebhookDeliveryAttempt a) {
            return new AttemptDto(a.attemptNo(), a.statusCode(), a.error(), a.durationMs(), a.responseSnippet(), a.createdAt());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeliveryDto(String id, String eventId, String eventType, String endpointId, String status, int attempts,
                              Instant nextAttemptAt, Integer lastStatusCode, String lastError, Instant deliveredAt,
                              Instant createdAt, List<AttemptDto> attemptLog) {
        public static DeliveryDto from(WebhookDelivery d, String eventType, List<AttemptDto> attempts) {
            return new DeliveryDto(d.id(), d.eventId(), eventType, d.endpointId(), d.status(), d.attempts(),
                    WebhookDelivery.PENDING.equals(d.status()) ? d.nextAttemptAt() : null, d.lastStatusCode(),
                    d.lastError(), d.deliveredAt(), d.createdAt(), attempts);
        }
    }
}
