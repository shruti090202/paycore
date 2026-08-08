package com.paycore.payments;

import com.paycore.banksim.BankGateway;
import com.paycore.banksim.TestCards;
import com.paycore.common.error.PayCoreException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardValidatorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC);

    private static BankGateway.CardDetails card(String number, int month, int year, String cvc) {
        return new BankGateway.CardDetails(number, month, year, cvc);
    }

    @Test
    void everyDocumentedTestCardIsLuhnValidAndUnique() {
        assertThat(TestCards.ALL).extracting(TestCards.TestCard::number).doesNotHaveDuplicates();
        for (TestCards.TestCard c : TestCards.ALL) {
            assertThat(CardValidator.luhn(c.number())).as(c.number()).isTrue();
            assertThat(CardValidator.validate(card(c.number(), 12, 2030, "123"), CLOCK)).isEqualTo(c);
        }
    }

    @Test
    void acceptsSpacesAndDashesInTheNumber() {
        assertThat(CardValidator.validate(card("4242 4242 4242 4242", 12, 30, "123"), CLOCK).brand()).isEqualTo("visa");
        assertThat(CardValidator.validate(card("4242-4242-4242-4242", 12, 2030, "1234"), CLOCK).brand()).isEqualTo("visa");
    }

    @Test
    void rejectsRealLookingCardsThatAreNotTestCards() {
        // Luhn-valid but not on the allowlist: must be refused before it reaches the simulator.
        assertThatThrownBy(() -> CardValidator.validate(card("4111111111111111", 12, 2030, "123"), CLOCK))
                .isInstanceOf(PayCoreException.class)
                .satisfies(e -> {
                    assertThat(((PayCoreException) e).code()).isEqualTo("card_not_test_card");
                    assertThat(e.getMessage()).doesNotContain("4111");
                });
    }

    @Test
    void rejectsMalformedInput() {
        assertThatThrownBy(() -> CardValidator.validate(card("4242424242424241", 12, 2030, "123"), CLOCK))
                .satisfies(e -> assertThat(((PayCoreException) e).code()).isEqualTo("card_number_invalid"));
        assertThatThrownBy(() -> CardValidator.validate(card("42", 12, 2030, "123"), CLOCK))
                .satisfies(e -> assertThat(((PayCoreException) e).code()).isEqualTo("card_number_invalid"));
        assertThatThrownBy(() -> CardValidator.validate(card("4242424242424242", 13, 2030, "123"), CLOCK))
                .satisfies(e -> assertThat(((PayCoreException) e).code()).isEqualTo("card_expiry_invalid"));
        assertThatThrownBy(() -> CardValidator.validate(card("4242424242424242", 8, 2026, "123"), CLOCK))
                .satisfies(e -> assertThat(((PayCoreException) e).code()).isEqualTo("card_expired"));
        assertThatThrownBy(() -> CardValidator.validate(card("4242424242424242", 12, 2030, "12"), CLOCK))
                .satisfies(e -> assertThat(((PayCoreException) e).code()).isEqualTo("card_cvc_invalid"));
        // Current month is still valid.
        assertThat(CardValidator.validate(card("4242424242424242", 9, 2026, "123"), CLOCK)).isNotNull();
    }

    @Test
    void cardDetailsNeverPrintThePan() {
        assertThat(card("4242424242424242", 12, 2030, "123").toString()).doesNotContain("42424242").contains("****4242");
    }
}
