package com.paycore.webhooks;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Delivery tuning. Defaults: 8 attempts over ~1 hour of exponential backoff (10s, 20s, 40s, ... capped at
 * 15 min, with full jitter), 10s HTTP timeout, 60s lease while a dispatcher holds a delivery.
 */
@ConfigurationProperties(prefix = "paycore.webhooks")
public record WebhookProperties(
        int maxAttempts,
        long baseDelaySeconds,
        long maxDelaySeconds,
        long leaseSeconds,
        long httpTimeoutSeconds,
        int batchSize,
        boolean allowPrivateUrls
) {
    public WebhookProperties {
        if (maxAttempts <= 0) maxAttempts = 8;
        if (baseDelaySeconds < 0) baseDelaySeconds = 10;
        if (maxDelaySeconds <= 0) maxDelaySeconds = 900;
        if (leaseSeconds <= 0) leaseSeconds = 60;
        if (httpTimeoutSeconds <= 0) httpTimeoutSeconds = 10;
        if (batchSize <= 0) batchSize = 25;
    }
}
