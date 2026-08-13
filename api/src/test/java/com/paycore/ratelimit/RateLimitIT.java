package com.paycore.ratelimit;

import com.paycore.support.AbstractIntegrationTest;
import com.paycore.support.Api;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitIT extends AbstractIntegrationTest {

    @Autowired StringRedisTemplate redis;
    @Autowired RateLimitPolicy policy;
    @Autowired JdbcClient jdbc;

    /** A clock the test can move forward, so refill is deterministic. */
    static class ManualClock extends Clock {
        Instant now = Instant.parse("2026-09-12T00:00:00Z");

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    @Test
    void bucketRefillsAtTheConfiguredRateAndIsAtomic() {
        ManualClock clock = new ManualClock();
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(redis, clock);
        String key = "rl:test:" + UUID.randomUUID();

        // capacity 3, refill 2 tokens/second
        for (int i = 3; i >= 1; i--) {
            TokenBucketRateLimiter.Decision d = limiter.tryConsume(key, 3, 2.0);
            assertThat(d.allowed()).isTrue();
            assertThat(d.remaining()).isEqualTo(i - 1);
            assertThat(d.degraded()).isFalse();
        }
        TokenBucketRateLimiter.Decision denied = limiter.tryConsume(key, 3, 2.0);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.remaining()).isZero();
        assertThat(denied.retryAfterMs()).isEqualTo(500);       // one token at 2/s = 500 ms

        clock.advance(Duration.ofMillis(499));
        assertThat(limiter.tryConsume(key, 3, 2.0).allowed()).isFalse();
        clock.advance(Duration.ofMillis(1));
        assertThat(limiter.tryConsume(key, 3, 2.0).allowed()).isTrue();

        clock.advance(Duration.ofSeconds(10));                    // refills to capacity, never beyond
        assertThat(limiter.tryConsume(key, 3, 2.0).remaining()).isEqualTo(2);

        // Zero refill = hard cap.
        String hard = "rl:test:" + UUID.randomUUID();
        limiter.tryConsume(hard, 1, 0);
        TokenBucketRateLimiter.Decision capped = limiter.tryConsume(hard, 1, 0);
        assertThat(capped.allowed()).isFalse();
        assertThat(capped.retryAfterMs()).isEqualTo(-1);
    }

    @Test
    void failsOpenWhenRedisIsUnreachable() {
        LettuceConnectionFactory broken = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 1),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(300)).build());
        broken.afterPropertiesSet();
        try {
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(new StringRedisTemplate(broken), Clock.systemUTC());
            TokenBucketRateLimiter.Decision d = limiter.tryConsume("rl:test:broken", 5, 1.0);
            assertThat(d.allowed()).isTrue();
            assertThat(d.degraded()).isTrue();
        } finally {
            broken.destroy();
        }
    }

    @Test
    void merchantOverrideProducesHeadersAnd429() {
        Api api = api();
        String key = api.signupAndGetApiKey();
        String merchantId = api.get("/v1/account", Api.bearer(key)).text("/id");
        // The account call above already consumed one token under the generous default; now tighten this merchant.
        jdbc.sql("UPDATE merchants SET rate_limit_capacity = 3, rate_limit_refill_per_second = 0.001 WHERE id = :m")
                .param("m", merchantId).update();
        policy.invalidate(merchantId);
        redis.delete("rl:" + api.get("/v1/account", Api.bearer(key)).text("/api_key_id")); // fresh bucket
        // (that call was made under the old cached limit; from here the new limit applies)

        for (int expectedRemaining = 2; expectedRemaining >= 0; expectedRemaining--) {
            Api.Response r = api.get("/v1/account", Api.bearer(key));
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.headers().getFirst("X-RateLimit-Limit")).isEqualTo("3");
            assertThat(r.headers().getFirst("X-RateLimit-Remaining")).isEqualTo(String.valueOf(expectedRemaining));
            assertThat(r.headers().getFirst("X-RateLimit-Reset")).isNotNull();
        }
        Api.Response limited = api.get("/v1/account", Api.bearer(key));
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.text("/error/type")).isEqualTo("rate_limited");
        assertThat(limited.text("/error/code")).isEqualTo("rate_limit_exceeded");
        assertThat(limited.headers().getFirst("Retry-After")).isNotNull();
        assertThat(Long.parseLong(limited.headers().getFirst("Retry-After"))).isGreaterThanOrEqualTo(1);
        assertThat(limited.headers().getFirst("X-RateLimit-Remaining")).isEqualTo("0");

        // Another merchant's key is a different bucket with the default limit.
        Api.Response other = api.get("/v1/account", Api.bearer(api.signupAndGetApiKey()));
        assertThat(other.status()).isEqualTo(200);
        assertThat(other.headers().getFirst("X-RateLimit-Limit")).isEqualTo("100000");

        // Unauthenticated requests are not rate limited (they are rejected, and do not consume anyone's tokens).
        assertThat(api.get("/v1/account", Api.none()).headers().getFirst("X-RateLimit-Limit")).isNull();
    }
}
