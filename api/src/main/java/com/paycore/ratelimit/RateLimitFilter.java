package com.paycore.ratelimit;

import com.paycore.auth.MerchantAuthentication;
import com.paycore.auth.MerchantPrincipal;
import com.paycore.common.error.ApiError;
import com.paycore.common.error.ErrorType;
import com.paycore.common.web.CorrelationIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Per-API-key rate limiting on the merchant API. Runs after authentication (the bucket is keyed by API key,
 * falling back to merchant for JWT-less audiences) and before idempotency (a replay still costs a token,
 * as at Stripe). Standard headers on every response; 429 + Retry-After when the bucket is empty.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final TokenBucketRateLimiter limiter;
    private final RateLimitPolicy policy;
    private final RateLimitProperties props;
    private final ObjectMapper mapper;

    public RateLimitFilter(TokenBucketRateLimiter limiter, RateLimitPolicy policy, RateLimitProperties props,
                           ObjectMapper mapper) {
        this.limiter = limiter;
        this.policy = policy;
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !props.enabled();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof MerchantAuthentication ma)) {
            chain.doFilter(request, response);
            return;
        }
        MerchantPrincipal p = ma.getPrincipal();
        RateLimitPolicy.Limit limit = policy.forMerchant(p.merchantId());
        String bucket = "rl:" + (p.apiKeyId() != null ? p.apiKeyId() : p.merchantId());
        TokenBucketRateLimiter.Decision d = limiter.tryConsume(bucket, limit.capacity(), limit.refillPerSecond());

        response.setHeader("X-RateLimit-Limit", String.valueOf(d.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, d.remaining())));
        response.setHeader("X-RateLimit-Reset", String.valueOf((d.resetMs() + 999) / 1000));
        if (d.degraded()) {
            response.setHeader("X-RateLimit-Degraded", "true");
        }
        if (!d.allowed()) {
            long retrySeconds = d.retryAfterMs() < 0 ? 60 : Math.max(1, (d.retryAfterMs() + 999) / 1000);
            response.setHeader("Retry-After", String.valueOf(retrySeconds));
            response.setStatus(ErrorType.RATE_LIMITED.status().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(mapper.writeValueAsString(ApiError.of(ErrorType.RATE_LIMITED, "rate_limit_exceeded",
                    "Too many requests for this API key; retry after " + retrySeconds + "s", null,
                    CorrelationIdFilter.current(request))));
            return;
        }
        chain.doFilter(request, response);
    }
}
