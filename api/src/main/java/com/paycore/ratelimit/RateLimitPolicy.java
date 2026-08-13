package com.paycore.ratelimit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which limit applies to a merchant: its own override or the platform default. Cached in memory for a minute so
 * authenticating a request does not add a merchant lookup on the hot path.
 */
@Component
public class RateLimitPolicy {

    public record Limit(int capacity, double refillPerSecond) {
    }

    private record Cached(Limit limit, long expiresAtMs) {
    }

    private static final long CACHE_MS = 60_000;

    private final JdbcClient jdbc;
    private final RateLimitProperties defaults;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public RateLimitPolicy(JdbcClient jdbc, RateLimitProperties defaults, Clock clock) {
        this.jdbc = jdbc;
        this.defaults = defaults;
        this.clock = clock;
    }

    public Limit forMerchant(String merchantId) {
        long now = clock.millis();
        Cached c = cache.get(merchantId);
        if (c != null && c.expiresAtMs() > now) {
            return c.limit();
        }
        Limit limit = load(merchantId);
        cache.put(merchantId, new Cached(limit, now + CACHE_MS));
        return limit;
    }

    public void invalidate(String merchantId) {
        cache.remove(merchantId);
    }

    private Limit load(String merchantId) {
        return jdbc.sql("SELECT rate_limit_capacity, rate_limit_refill_per_second FROM merchants WHERE id = :id")
                .param("id", merchantId)
                .query((rs, i) -> {
                    Integer cap = (Integer) rs.getObject("rate_limit_capacity");
                    Double refill = (Double) rs.getObject("rate_limit_refill_per_second");
                    return new Limit(cap == null ? defaults.capacity() : cap,
                            refill == null ? defaults.refillPerSecond() : refill);
                })
                .optional()
                .orElse(new Limit(defaults.capacity(), defaults.refillPerSecond()));
    }
}
