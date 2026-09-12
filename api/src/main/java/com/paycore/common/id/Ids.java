package com.paycore.common.id;

import com.github.f4b6a3.ulid.UlidCreator;

/** Prefixed, time-sortable identifiers (Stripe-style): pay_01j9x.... */
public final class Ids {

    public static final String MERCHANT = "mer";
    public static final String API_KEY = "key";
    public static final String PAYMENT = "pay";
    public static final String REFUND = "rf";
    public static final String JOURNAL_ENTRY = "je";
    public static final String POSTING = "pst";
    public static final String ACCOUNT = "acct";
    public static final String EVENT = "evt";
    public static final String WEBHOOK_ENDPOINT = "whe";
    public static final String WEBHOOK_DELIVERY = "whd";
    public static final String REQUEST = "req";

    private Ids() {
    }

    public static String newId(String prefix) {
        return prefix + "_" + UlidCreator.getMonotonicUlid().toLowerCase();
    }

    public static boolean hasPrefix(String id, String prefix) {
        return id != null && id.startsWith(prefix + "_");
    }
}
