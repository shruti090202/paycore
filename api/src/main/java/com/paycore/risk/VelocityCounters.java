package com.paycore.risk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/** Sliding-window-ish counters in Redis, one Lua call each (Upstash bills per command). */
@Component
public class VelocityCounters {

    private static final Logger log = LoggerFactory.getLogger(VelocityCounters.class);

    // INCR + set TTL on first hit
    private static final RedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>("""
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1])) end
            return c
            """, Long.class);

    // SADD member, refresh TTL, return set size
    private static final RedisScript<Long> SADD_COUNT = new DefaultRedisScript<>("""
            redis.call('SADD', KEYS[1], ARGV[1])
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
            return redis.call('SCARD', KEYS[1])
            """, Long.class);

    private final StringRedisTemplate redis;

    public VelocityCounters(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Attempts with this card at this merchant in the window (including this one), or null if unavailable. */
    public Long incrementCardAttempts(String merchantId, String cardFingerprint, long windowSeconds) {
        try {
            return redis.execute(INCR_WITH_TTL, List.of("risk:card:" + merchantId + ":" + cardFingerprint), String.valueOf(windowSeconds));
        } catch (RuntimeException e) {
            log.warn("velocity counter unavailable: {}", e.getMessage());
            return null;
        }
    }

    /** Distinct cards this customer has used with this merchant in the window, or null if unavailable. */
    public Long recordCustomerCard(String merchantId, String customerEmail, String cardFingerprint, long windowSeconds) {
        try {
            String key = "risk:cust:" + merchantId + ":" + BlocklistRepository.hashEmail(customerEmail);
            return redis.execute(SADD_COUNT, List.of(key), cardFingerprint, String.valueOf(windowSeconds));
        } catch (RuntimeException e) {
            log.warn("velocity counter unavailable: {}", e.getMessage());
            return null;
        }
    }
}
