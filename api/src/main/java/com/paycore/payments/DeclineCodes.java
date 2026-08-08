package com.paycore.payments;

import java.util.Map;

/** Customer-facing wording for bank decline codes. Fraud-related declines deliberately say nothing specific. */
public final class DeclineCodes {

    private static final Map<String, String> MESSAGES = Map.of(
            "generic_decline", "Your card was declined.",
            "insufficient_funds", "Your card has insufficient funds.",
            "expired_card", "Your card has expired.",
            "incorrect_cvc", "The card's security code is incorrect.",
            "fraudulent", "Your card was declined.",
            "processing_error", "An error occurred while processing your card. Try again in a moment.",
            "refund_declined", "The bank declined the refund.",
            "original_not_found", "The bank has no record of the original payment.",
            "bank_unreachable", "The bank never received the request."
    );

    private DeclineCodes() {
    }

    public static String message(String code) {
        return MESSAGES.getOrDefault(code, "Your card was declined.");
    }
}
