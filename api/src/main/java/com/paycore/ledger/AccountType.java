package com.paycore.ledger;

/** Standard account classes. Assets/expenses grow with debits; liabilities/revenue grow with credits. */
public enum AccountType {
    ASSET, LIABILITY, REVENUE, EXPENSE;

    public String wire() {
        return name().toLowerCase();
    }

    public static AccountType fromWire(String s) {
        return valueOf(s.toUpperCase());
    }
}
