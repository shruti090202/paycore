package com.paycore.payments;

import java.util.Map;

/** Customer-facing wording for decline codes. Fraud/risk declines deliberately say nothing specific. */
public final class DeclineCodes {

    private static final String GENERIC = "Your card was declined.";

    private static final Map<String, String> MESSAGES = Map.ofEntries(
            Map.entry("generic_decline", GENERIC),
            Map.entry("insufficient_funds", "Your card has insufficient funds."),
            Map.entry("expired_card", "Your card has expired."),
            Map.entry("incorrect_cvc", "The card's security code is incorrect."),
            Map.entry("fraudulent", GENERIC),
            Map.entry("risk_blocked", GENERIC),
            Map.entry("card_declined", GENERIC),
            Map.entry("processing_error", "An error occurred while processing your card. Try again in a moment."),
            Map.entry("refund_declined", "The bank declined the refund."),
            Map.entry("original_not_found", "The bank has no record of the original payment."),
            Map.entry("bank_unreachable", "The bank never received the request.")
    );

    private DeclineCodes() {
    }

    public static String message(String code) {
        return MESSAGES.getOrDefault(code, GENERIC);
    }
}
