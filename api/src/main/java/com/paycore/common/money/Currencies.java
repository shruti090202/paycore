package com.paycore.common.money;

import java.util.Map;
import java.util.Set;

/**
 * The currencies PayCore accepts and their minor-unit exponents (ISO 4217).
 * JPY is deliberately included: an exponent of 0 is the classic bug source when "amount / 100" is hard-coded.
 */
public final class Currencies {

    private static final Map<String, Integer> EXPONENTS = Map.of(
            "INR", 2,
            "USD", 2,
            "EUR", 2,
            "GBP", 2,
            "SGD", 2,
            "AED", 2,
            "JPY", 0
    );

    public static final String DEFAULT = "INR";

    private Currencies() {
    }

    public static boolean isSupported(String code) {
        return code != null && EXPONENTS.containsKey(code);
    }

    public static Set<String> supported() {
        return EXPONENTS.keySet();
    }

    public static int exponent(String code) {
        Integer e = EXPONENTS.get(code);
        if (e == null) {
            throw new IllegalArgumentException("Unsupported currency: " + code);
        }
        return e;
    }
}
