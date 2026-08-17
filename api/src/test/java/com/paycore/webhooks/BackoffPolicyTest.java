package com.paycore.webhooks;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffPolicyTest {

    private final BackoffPolicy policy = new BackoffPolicy(Duration.ofSeconds(10), Duration.ofMinutes(15), 8);

    @Test
    void ceilingDoublesAndIsCapped() {
        assertThat(policy.ceiling(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.ceiling(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(policy.ceiling(3)).isEqualTo(Duration.ofSeconds(40));
        assertThat(policy.ceiling(7)).isEqualTo(Duration.ofSeconds(640));
        assertThat(policy.ceiling(8)).isEqualTo(Duration.ofMinutes(15));
        assertThat(policy.ceiling(40)).as("no overflow for absurd attempt counts").isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void delaysAreJitteredWithinTheCeiling() {
        for (int attempt = 1; attempt <= 8; attempt++) {
            Duration ceil = policy.ceiling(attempt);
            for (int i = 0; i < 200; i++) {
                Duration d = policy.nextDelay(attempt);
                assertThat(d).isBetween(Duration.ZERO, ceil);
            }
        }
        // Full jitter really spreads: 200 samples at attempt 8 are not all identical.
        long distinct = java.util.stream.IntStream.range(0, 200).mapToLong(i -> policy.nextDelay(8).toMillis()).distinct().count();
        assertThat(distinct).isGreaterThan(50);
    }

    @Test
    void exhaustionAfterMaxAttempts() {
        assertThat(policy.exhausted(7)).isFalse();
        assertThat(policy.exhausted(8)).isTrue();
        assertThat(new BackoffPolicy(Duration.ZERO, Duration.ofSeconds(1), 3).nextDelay(1)).isEqualTo(Duration.ZERO);
    }
}
