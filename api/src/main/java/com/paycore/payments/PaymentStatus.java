package com.paycore.payments;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** The payment state machine. */
public enum PaymentStatus {
    CREATED,
    PENDING_BANK,
    AUTHORIZED,
    CAPTURED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    FAILED,
    CANCELED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> TRANSITIONS = Map.of(
            CREATED, EnumSet.of(PENDING_BANK, AUTHORIZED, FAILED, CANCELED),
            PENDING_BANK, EnumSet.of(AUTHORIZED, FAILED),
            AUTHORIZED, EnumSet.of(CAPTURED, CANCELED, FAILED),
            CAPTURED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED),
            PARTIALLY_REFUNDED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED),
            REFUNDED, EnumSet.noneOf(PaymentStatus.class),
            FAILED, EnumSet.noneOf(PaymentStatus.class),
            CANCELED, EnumSet.noneOf(PaymentStatus.class)
    );

    public boolean canTransitionTo(PaymentStatus next) {
        return TRANSITIONS.get(this).contains(next);
    }

    public Set<PaymentStatus> allowedNext() {
        return TRANSITIONS.get(this);
    }

    public boolean isTerminal() {
        return TRANSITIONS.get(this).isEmpty();
    }

    /** Money has been captured (fully or partly refunded still counts). */
    public boolean isCaptured() {
        return this == CAPTURED || this == PARTIALLY_REFUNDED || this == REFUNDED;
    }

    public String wire() {
        return name().toLowerCase();
    }

    public static PaymentStatus fromWire(String s) {
        return valueOf(s.toUpperCase());
    }
}
