package com.paycore.banksim;

import java.util.concurrent.atomic.AtomicReference;

/** Runtime-adjustable simulator knobs (dashboard "simulator" panel). */
public class BankSimConfig {

    public record Settings(double randomDeclineRate, double randomTimeoutRate, int minLatencyMs, int maxLatencyMs,
                           double settlementAnomalyRate) {
        public Settings {
            if (!unit(randomDeclineRate) || !unit(randomTimeoutRate) || !unit(settlementAnomalyRate)) {
                throw new IllegalArgumentException("rates must be between 0 and 1");
            }
            if (minLatencyMs < 0 || maxLatencyMs < minLatencyMs || maxLatencyMs > 10_000) {
                throw new IllegalArgumentException("latency must satisfy 0 <= min <= max <= 10000");
            }
        }

        private static boolean unit(double v) {
            return v >= 0 && v <= 1;
        }
    }

    public static final Settings DEFAULTS = new Settings(0.0, 0.0, 10, 60, 0.0);

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
