package com.paycore.common.activity;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityGateTest {

    @Test
    void activeOnlyWithinTheWindowAfterATouch() {
        Instant t0 = Instant.parse("2026-09-12T00:00:00Z");
        Clock[] clock = {Clock.fixed(t0, ZoneOffset.UTC)};
        ActivityGate gate = new ActivityGate(new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId z) { return this; }
            @Override public Instant instant() { return clock[0].instant(); }
        }, Duration.ofMinutes(10));

        assertThat(gate.isActive()).as("quiet at startup: nothing polls, Neon may sleep").isFalse();
        gate.touch();
        assertThat(gate.isActive()).isTrue();
        clock[0] = Clock.fixed(t0.plus(Duration.ofMinutes(9)), ZoneOffset.UTC);
        assertThat(gate.isActive()).isTrue();
        clock[0] = Clock.fixed(t0.plus(Duration.ofMinutes(10)).plusSeconds(1), ZoneOffset.UTC);
        assertThat(gate.isActive()).isFalse();

        // A touch never shortens an existing window.
        clock[0] = Clock.fixed(t0, ZoneOffset.UTC);
        gate.touch();
        Instant until = gate.activeUntil();
        gate.touch();
        assertThat(gate.activeUntil()).isEqualTo(until);
    }
}
