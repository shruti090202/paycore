package com.paycore.webhooks;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with full jitter: {@code delay = random(0, min(cap, base * 2^(attempt-1)))}.
 * Full jitter (rather than "base * 2^n + small jitter") spreads retries from many failed deliveries to one
 * dead endpoint across the whole window instead of hammering it in synchronized waves.
 */
public final class BackoffPolicy {

    private final Duration base;
    private final Duration cap;
    private final int maxAttempts;

    public BackoffPolicy(Duration base, Duration cap, int maxAttempts) {
        this.base = base;
        this.cap = cap;
        this.maxAttempts = maxAttempts;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /** True when the attempt that just failed was the last one allowed. */
    public boolean exhausted(int attemptsMade) {
        return attemptsMade >= maxAttempts;
    }

    /** Upper bound of the window for the next retry after {@code attemptsMade} failed attempts. */
    public Duration ceiling(int attemptsMade) {
        long baseMs = base.toMillis();
        if (baseMs <= 0) {
            return Duration.ZERO; // immediate retries (tests, or a deliberately aggressive policy)
        }
        int exp = Math.max(0, Math.min(attemptsMade - 1, 30));
        long millis = baseMs << exp;
        return millis < 0 || millis > cap.toMillis() ? cap : Duration.ofMillis(millis);
    }

    public Duration nextDelay(int attemptsMade) {
        long ceil = ceiling(attemptsMade).toMillis();
        if (ceil <= 0) {
            return Duration.ZERO;
        }
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(ceil + 1));
    }
}
