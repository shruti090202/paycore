package com.paycore.payments;

import com.paycore.common.money.Money;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FeePolicyTest {

    @Test
    void percentPlusFixed() {
        // 100.00 INR at 2% + 3.00 = 2.00 + 3.00 = 5.00
        assertThat(FeePolicy.feeFor(Money.of(10_000, "INR"), 200, 300)).isEqualTo(Money.of(500, "INR"));
    }

    @Test
    void feeNeverExceedsTheCapturedAmount() {
        // 1.00 INR captured: 2% = 0.02 + 3.00 fixed = 3.02 > 1.00 -> capped at 1.00, merchant net is 0, never negative
        assertThat(FeePolicy.feeFor(Money.of(100, "INR"), 200, 300)).isEqualTo(Money.of(100, "INR"));
    }

    @Test
    void zeroFeesAreAllowed() {
        assertThat(FeePolicy.feeFor(Money.of(10_000, "INR"), 0, 0)).isEqualTo(Money.zero("INR"));
    }
}
