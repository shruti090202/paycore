package com.paycore.payments;

import java.security.SecureRandom;

/**
 * Hosted-checkout session tokens ({@code cs_...}). The token is the only credential the browser holds, so it
 * must be unguessable (~190 bits) and is tied to exactly one payment.
 */
final class CheckoutTokens {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private CheckoutTokens() {
    }

    static String newToken() {
        StringBuilder sb = new StringBuilder("cs_");
        for (int i = 0; i < 32; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
