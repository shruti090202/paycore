package com.paycore.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.util.List;

/**
 * Token bucket in Redis, evaluated by ONE Lua script so read-refill-consume-write is atomic: two requests
 * arriving at the same millisecond cannot both take the last token. One round-trip per request
 * (EVALSHA; Spring falls back to EVAL when the script is not cached).
 * <p>
 * Time comes from the caller (this JVM), not from Redis, so a test can drive the clock and so behaviour is
 * identical across Redis versions that expose different time commands inside scripts.
 * <p>
 * Fail-open: if Redis is unreachable the request is allowed and flagged degraded. Rate limiting protects
 * capacity; it must never turn a Redis outage into a payments outage.
 */
public class TokenBucketRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);

    // KEYS[1] bucket key; ARGV: capacity, refill_per_second, now_ms, requested
    // returns: {allowed(1|0), remaining_tokens(int), retry_after_ms, reset_ms}
    private static final String LUA = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])

            local state = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(state[1])
            local ts = tonumber(state[2])
            if tokens == nil then
              tokens = capacity
              ts = now
            end
            if now > ts then
              tokens = math.min(capacity, tokens + (now - ts) / 1000 * refill)
            else
              now = ts
            end

            local allowed = 0
            local retry_after = 0
            if tokens >= requested then
              tokens = tokens - requested
              allowed = 1
            else
              if refill > 0 then
                retry_after = math.ceil((requested - tokens) / refill * 1000)
              else
                retry_after = -1
              end
            end

            local reset_ms = 0
            if refill > 0 and tokens < capacity then
              reset_ms = math.ceil((capacity - tokens) / refill * 1000)
            end

            redis.call('HSET', key, 'tokens', tokens, 'ts', now)
            local ttl = 60000
            if refill > 0 then
              ttl = math.max(ttl, math.ceil(capacity / refill * 1000) + 1000)
            end
            redis.call('PEXPIRE', key, ttl)
            return {allowed, math.floor(tokens), retry_after, reset_ms}
            """;

    private static final RedisScript<List> SCRIPT = new DefaultRedisScript<>(LUA, List.class);

    public record Decision(boolean allowed, int limit, int remaining, long retryAfterMs, long resetMs, boolean degraded) {
        public static Decision degraded(int limit) {
            return new Decision(true, limit, limit, 0, 0, true);
        }
    }

    private final StringRedisTemplate redis;
    private final Clock clock;

    public TokenBucketRateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @SuppressWarnings("unchecked")
    public Decision tryConsume(String bucketKey, int capacity, double refillPerSecond) {
        try {
            List<Object> r = redis.execute(SCRIPT, List.of(bucketKey),
                    String.valueOf(capacity), String.valueOf(refillPerSecond),
                    String.valueOf(clock.millis()), "1");
            if (r == null || r.size() < 4) {
                return Decision.degraded(capacity);
            }
            boolean allowed = ((Number) r.get(0)).longValue() == 1;
            int remaining = ((Number) r.get(1)).intValue();
            long retryAfter = ((Number) r.get(2)).longValue();
            long reset = ((Number) r.get(3)).longValue();
            return new Decision(allowed, capacity, remaining, retryAfter, reset, false);
        } catch (RuntimeException e) {
            log.warn("rate limiter degraded (Redis unavailable): {}", e.getMessage());
            return Decision.degraded(capacity);
        }
    }
}
