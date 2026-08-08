package com.paycore.payments;

import com.paycore.banksim.BankGateway;
import com.paycore.banksim.TestCards;
import com.paycore.common.error.ErrorType;
import com.paycore.common.error.PayCoreException;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * Gatekeeper for card input. Order matters: format and Luhn first (cheap, no information leak), then the
 * test-card allowlist. A real-looking PAN that is not a documented test card is rejected here and never
 * reaches the simulator, storage, or logs.
 */
public final class CardValidator {

    private CardValidator() {
    }

    public static TestCards.TestCard validate(BankGateway.CardDetails card, Clock clock) {
        String number = card.number() == null ? "" : card.number().replaceAll("[\\s-]", "");
        if (!number.matches("\\d{13,19}")) {
            throw invalid("card_number_invalid", "Card number must be 13-19 digits", "card_number");
        }
        if (!luhn(number)) {
            throw invalid("card_number_invalid", "Card number failed the Luhn check", "card_number");
        }
        if (card.expMonth() < 1 || card.expMonth() > 12) {
            throw invalid("card_expiry_invalid", "Expiry month must be 1-12", "exp_month");
        }
        int year = card.expYear() < 100 ? 2000 + card.expYear() : card.expYear();
        YearMonth exp = YearMonth.of(year, card.expMonth());
        if (exp.isBefore(YearMonth.now(clock.withZone(ZoneOffset.UTC)))) {
            throw invalid("card_expired", "Card has expired", "exp_year");
        }
        if (card.cvc() == null || !card.cvc().matches("\\d{3,4}")) {
            throw invalid("card_cvc_invalid", "CVC must be 3 or 4 digits", "cvc");
        }
        return TestCards.find(number).orElseThrow(() -> new PayCoreException(ErrorType.INVALID_REQUEST,
                "card_not_test_card",
                "PayCore is a simulator and accepts only its documented test card numbers. Never enter a real card.",
                "card_number"));
    }

    static boolean luhn(String digits) {
        int sum = 0;
        boolean alternate = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (alternate) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }

    private static PayCoreException invalid(String code, String message, String param) {
        return new PayCoreException(ErrorType.VALIDATION_ERROR, code, message, param);
    }
}
