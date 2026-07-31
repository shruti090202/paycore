package com.paycore.common.money;

import java.math.BigDecimal;

/**
 * An amount in minor units (paise, cents) with its ISO currency. Integer arithmetic only.
 * <p>
 * Mixed-currency arithmetic throws: there is no exchange rate inside a ledger, so adding INR to USD is a bug,
 * not a rounding question. Percent fees use half-up rounding on the integer product to avoid any floating point.
 */
public record Money(long minor, String currency) implements Comparable<Money> {

    public Money {
        if (!Currencies.isSupported(currency)) {
            throw new IllegalArgumentException("Unsupported currency: " + currency);
        }
    }

    public static Money of(long minor, String currency) {
        return new Money(minor, currency);
    }

    public static Money zero(String currency) {
        return new Money(0, currency);
    }

    public Money plus(Money other) {
        assertSameCurrency(other);
        return new Money(Math.addExact(minor, other.minor), currency);
    }

    public Money minus(Money other) {
        assertSameCurrency(other);
        return new Money(Math.subtractExact(minor, other.minor), currency);
    }

    /** {@code amount * bps / 10_000}, rounded half-up, computed entirely in integers. */
    public Money percentBps(int bps) {
        if (bps < 0 || bps > 10_000) {
            throw new IllegalArgumentException("bps out of range: " + bps);
        }
        long product = Math.multiplyExact(minor, (long) bps);
        long rounded = Math.floorDiv(product + 5_000, 10_000);
        return new Money(rounded, currency);
    }

    public boolean isPositive() {
        return minor > 0;
    }

    public boolean isZero() {
        return minor == 0;
    }

    public boolean isNegative() {
        return minor < 0;
    }

    public boolean isGreaterThan(Money other) {
        assertSameCurrency(other);
        return minor > other.minor;
    }

    public Money min(Money other) {
        assertSameCurrency(other);
        return minor <= other.minor ? this : other;
    }

    /** Major-unit decimal for display only (e.g. 12345 INR -> 123.45). Never used for arithmetic. */
    public BigDecimal toMajor() {
        return BigDecimal.valueOf(minor, Currencies.exponent(currency));
    }

    @Override
    public int compareTo(Money other) {
        assertSameCurrency(other);
        return Long.compare(minor, other.minor);
    }

    private void assertSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    @Override
    public String toString() {
        return toMajor().toPlainString() + " " + currency;
    }
}
