package com.paycore.payments;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static com.paycore.payments.PaymentStatus.AUTHORIZED;
import static com.paycore.payments.PaymentStatus.CANCELED;
import static com.paycore.payments.PaymentStatus.CAPTURED;
import static com.paycore.payments.PaymentStatus.CREATED;
import static com.paycore.payments.PaymentStatus.FAILED;
import static com.paycore.payments.PaymentStatus.PARTIALLY_REFUNDED;
import static com.paycore.payments.PaymentStatus.PENDING_BANK;
import static com.paycore.payments.PaymentStatus.REFUNDED;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentStatusTest {

    @Test
    void everyEdgeIsExplicit() {
        assertThat(CREATED.allowedNext()).containsExactlyInAnyOrder(PENDING_BANK, AUTHORIZED, FAILED, CANCELED);
        assertThat(PENDING_BANK.allowedNext()).containsExactlyInAnyOrder(AUTHORIZED, FAILED);
        assertThat(AUTHORIZED.allowedNext()).containsExactlyInAnyOrder(CAPTURED, CANCELED, FAILED);
        assertThat(CAPTURED.allowedNext()).containsExactlyInAnyOrder(PARTIALLY_REFUNDED, REFUNDED);
        assertThat(PARTIALLY_REFUNDED.allowedNext()).containsExactlyInAnyOrder(PARTIALLY_REFUNDED, REFUNDED);
        assertThat(REFUNDED.allowedNext()).isEmpty();
        assertThat(FAILED.allowedNext()).isEmpty();
        assertThat(CANCELED.allowedNext()).isEmpty();
    }

    @Test
    void moneyCanNeverMoveBackwards() {
        // Nothing captured can be canceled or un-captured; nothing terminal can change.
        for (PaymentStatus captured : Set.of(CAPTURED, PARTIALLY_REFUNDED, REFUNDED)) {
            assertThat(captured.canTransitionTo(CANCELED)).isFalse();
            assertThat(captured.canTransitionTo(AUTHORIZED)).isFalse();
            assertThat(captured.canTransitionTo(CREATED)).isFalse();
            assertThat(captured.isCaptured()).isTrue();
        }
        for (PaymentStatus terminal : Set.of(REFUNDED, FAILED, CANCELED)) {
            assertThat(terminal.isTerminal()).isTrue();
            for (PaymentStatus any : PaymentStatus.values()) {
                assertThat(terminal.canTransitionTo(any)).as(terminal + " -> " + any).isFalse();
            }
        }
    }

    @Test
    void unknownBankStateCanOnlyBeResolvedByTheBank() {
        // No cancel from pending_bank: money may have moved; only the bank's answer can decide.
        assertThat(PENDING_BANK.canTransitionTo(CANCELED)).isFalse();
        assertThat(PENDING_BANK.canTransitionTo(CAPTURED)).isFalse();
        assertThat(PENDING_BANK.isTerminal()).isFalse();
    }

    @Test
    void noSelfLoopsExceptRepeatedPartialRefunds() {
        for (PaymentStatus s : EnumSet.complementOf(EnumSet.of(PARTIALLY_REFUNDED))) {
            assertThat(s.canTransitionTo(s)).as(s + " -> " + s).isFalse();
        }
        assertThat(PARTIALLY_REFUNDED.canTransitionTo(PARTIALLY_REFUNDED)).isTrue();
    }

    @Test
    void wireNamesRoundTrip() {
        for (PaymentStatus s : PaymentStatus.values()) {
            assertThat(PaymentStatus.fromWire(s.wire())).isEqualTo(s);
            assertThat(s.wire()).matches("[a-z_]+");
        }
    }
}
