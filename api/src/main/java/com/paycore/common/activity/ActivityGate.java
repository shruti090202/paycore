package com.paycore.common.activity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/** "Is anything going on?" — the switch behind the in-process scheduler. */
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
