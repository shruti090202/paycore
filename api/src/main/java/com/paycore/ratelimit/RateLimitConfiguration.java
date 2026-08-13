package com.paycore.ratelimit;

import com.paycore.auth.MerchantApiFilter;
import jakarta.servlet.Filter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

@Configuration
public class RateLimitConfiguration {

    /** Runs first among contributed filters: a replayed idempotent request still costs a token. */
    public static final int ORDER = 100;

    @Bean
    public TokenBucketRateLimiter tokenBucketRateLimiter(StringRedisTemplate redis, Clock clock) {
        return new TokenBucketRateLimiter(redis, clock);
    }

    @Bean
    public MerchantApiFilter rateLimitApiFilter(TokenBucketRateLimiter limiter, RateLimitPolicy policy,
                                                RateLimitProperties props, ObjectMapper mapper) {
        RateLimitFilter filter = new RateLimitFilter(limiter, policy, props, mapper);
        return new MerchantApiFilter() {
            @Override
            public Filter filter() {
                return filter;
            }

            @Override
            public int order() {
                return ORDER;
            }
        };
    }
}
