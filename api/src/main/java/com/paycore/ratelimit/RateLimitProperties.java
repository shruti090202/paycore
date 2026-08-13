package com.paycore.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Platform defaults for the per-API-key token bucket. {@code capacity} is the burst size, {@code refillPerSecond}
 * the sustained rate (60 + 1/s = 60 requests/minute sustained with bursts of 60). Merchants can carry overrides.
 */
@ConfigurationProperties(prefix = "paycore.ratelimit")
public record RateLimitProperties(boolean enabled, int capacity, double refillPerSecond) {

    public RateLimitProperties {
        if (capacity <= 0) {
            capacity = 60;
        }
        if (refillPerSecond < 0) {
            refillPerSecond = 1.0;
        }
    }
}
