package com.paycore.ledger;

public enum Direction {
    DEBIT, CREDIT;

    public String wire() {
        return name().toLowerCase();
    }
}
