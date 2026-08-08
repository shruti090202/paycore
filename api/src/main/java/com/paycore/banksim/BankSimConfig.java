package com.paycore.banksim;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime-adjustable simulator knobs (dashboard "simulator" panel). In-memory on purpose: it is demo
 * configuration, not business data, and resetting on restart is the safe default.
 * <p>
 * Random decline/timeout rates only apply to cards whose deterministic behaviour is APPROVE, so the documented
 * test cards always behave as documented.
 */
public class BankSimConfig {

    public record Settings(double randomDeclineRate, double randomTimeoutRate, int minLatencyMs, int maxLatencyMs) {
        public Settings {
            if (randomDeclineRate < 0 || randomDeclineRate > 1 || randomTimeoutRate < 0 || randomTimeoutRate > 1) {
                throw new IllegalArgumentException("rates must be between 0 and 1");
            }
            if (minLatencyMs < 0 || maxLatencyMs < minLatencyMs || maxLatencyMs > 10_000) {
                throw new IllegalArgumentException("latency must satisfy 0 <= min <= max <= 10000");
            }
        }
    }

    public static final Settings DEFAULTS = new Settings(0.0, 0.0, 10, 60);

    private final AtomicReference<Settings> settings = new AtomicReference<>(DEFAULTS);

    public Settings get() {
        return settings.get();
    }

    public void set(Settings s) {
        settings.set(s);
    }

    public void reset() {
        settings.set(DEFAULTS);
    }
}
