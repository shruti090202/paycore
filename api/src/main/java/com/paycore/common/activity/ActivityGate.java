package com.paycore.common.activity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * "Is anything going on?" — the switch behind the in-process scheduler.
 * <p>
 * Free-tier constraint: Neon's compute suspends after 5 idle minutes and the monthly budget is ~100 CU-hours,
 * so a poller that hits Postgres every few seconds forever would burn the whole budget doing nothing.
 * Instead, work that produces background tasks (an outbox event, a bank timeout) calls {@link #touch()},
 * which keeps the scheduler active for a bounded window; when the window closes the scheduler goes quiet
 * and the database can sleep. Cron-triggered jobs remain the catch-up path.
 */
public class ActivityGate {

    private final Clock clock;
    private final Duration window;
    private final AtomicReference<Instant> activeUntil = new AtomicReference<>(Instant.EPOCH);

    public ActivityGate(Clock clock, Duration window) {
        this.clock = clock;
        this.window = window;
    }

    public void touch() {
        Instant until = clock.instant().plus(window);
        activeUntil.updateAndGet(cur -> cur.isAfter(until) ? cur : until);
    }

    public boolean isActive() {
        return activeUntil.get().isAfter(clock.instant());
    }

    public Instant activeUntil() {
        return activeUntil.get();
    }
}
