package com.paycore.common.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void arithmeticIsIntegerAndCurrencySafe() {
        Money a = Money.of(10_000, "INR");
        Money b = Money.of(2_500, "INR");
        assertThat(a.plus(b)).isEqualTo(Money.of(12_500, "INR"));
        assertThat(a.minus(b)).isEqualTo(Money.of(7_500, "INR"));
        assertThat(a.isGreaterThan(b)).isTrue();
        assertThat(a.min(b)).isEqualTo(b);
    }

    @Test
    void mixingCurrenciesIsABug() {
        assertThatThrownBy(() -> Money.of(1, "INR").plus(Money.of(1, "USD")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Currency mismatch");
        assertThatThrownBy(() -> Money.of(1, "XXX")).hasMessageContaining("Unsupported currency");
    }

    @Test
    void percentRoundsHalfUpWithoutFloatingPoint() {
        // 12345 * 2.00% = 246.9 -> 247
        assertThat(Money.of(12_345, "INR").percentBps(200).minor()).isEqualTo(247);
        // 12325 * 2.00% = 246.5 -> 247 (half-up)
        assertThat(Money.of(12_325, "INR").percentBps(200).minor()).isEqualTo(247);
        // 12300 * 2.00% = 246.0 -> 246
        assertThat(Money.of(12_300, "INR").percentBps(200).minor()).isEqualTo(246);
        // 1 paise at 2% = 0.02 -> 0
        assertThat(Money.of(1, "INR").percentBps(200).minor()).isEqualTo(0);
        assertThat(Money.of(1, "INR").percentBps(10_000)).isEqualTo(Money.of(1, "INR"));
    }

    @Test
    void overflowIsAnErrorNotAWrap() {
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE, "INR").plus(Money.of(1, "INR")))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE, "INR").percentBps(200))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void majorUnitsRespectTheCurrencyExponent() {
        assertThat(Money.of(12_345, "INR").toMajor()).isEqualByComparingTo(new BigDecimal("123.45"));
        assertThat(Money.of(12_345, "JPY").toMajor()).isEqualByComparingTo(new BigDecimal("12345"));
        assertThat(Money.of(12_345, "INR").toString()).isEqualTo("123.45 INR");
    }
}
