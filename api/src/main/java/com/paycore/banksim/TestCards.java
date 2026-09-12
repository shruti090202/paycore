package com.paycore.banksim;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The ONLY card numbers PayCore accepts. */
public final class TestCards {

    public enum Behaviour {
        APPROVE,
        DECLINE,
        /** The bank records an approval but never answers; a later lookup finds it approved. */
        TIMEOUT_THEN_APPROVED,
        /** The bank records a decline but never answers; a later lookup finds it declined. */
        TIMEOUT_THEN_DECLINED,
        /** The request is lost before the bank records anything; a later lookup finds nothing. */
        TIMEOUT_NOT_RECEIVED,
        /** Authorization approves; refunds against it are declined. */
        APPROVE_REFUND_DECLINES,
        /** Authorization approves; refunds against it time out (then resolve as approved). */
        APPROVE_REFUND_TIMEOUT
    }

    public record TestCard(String number, String brand, Behaviour behaviour, String declineCode, String description) {
        public String last4() {
            return number.substring(number.length() - 4);
        }
    }

    public static final List<TestCard> ALL = List.of(
            new TestCard("4242424242424242", "visa",       Behaviour.APPROVE, null, "Approved"),
            new TestCard("5555555555554444", "mastercard", Behaviour.APPROVE, null, "Approved (Mastercard)"),
            new TestCard("4000000000009235", "visa",       Behaviour.APPROVE, null, "Approved, flagged for risk review"),
            new TestCard("4000000000000002", "visa",       Behaviour.DECLINE, "generic_decline", "Declined"),
            new TestCard("4000000000009995", "visa",       Behaviour.DECLINE, "insufficient_funds", "Declined: insufficient funds"),
            new TestCard("4000000000000069", "visa",       Behaviour.DECLINE, "expired_card", "Declined: expired card"),
            new TestCard("4000000000000127", "visa",       Behaviour.DECLINE, "incorrect_cvc", "Declined: incorrect CVC"),
            new TestCard("4100000000000019", "visa",       Behaviour.DECLINE, "fraudulent", "Blocked by risk rules"),
            new TestCard("4000000000005126", "visa",       Behaviour.TIMEOUT_THEN_APPROVED, null, "Bank times out; later found approved"),
            new TestCard("4000000000000341", "visa",       Behaviour.TIMEOUT_THEN_DECLINED, "generic_decline", "Bank times out; later found declined"),
            new TestCard("4000000000000259", "visa",       Behaviour.TIMEOUT_NOT_RECEIVED, null, "Bank times out; request never received"),
            new TestCard("4000000000003063", "visa",       Behaviour.APPROVE_REFUND_DECLINES, "refund_declined", "Approved; refunds are declined"),
            new TestCard("4000000000003220", "visa",       Behaviour.APPROVE_REFUND_TIMEOUT, null, "Approved; refunds time out then succeed")
    );

    private static final Map<String, TestCard> BY_NUMBER = ALL.stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(TestCard::number, c -> c));

    private TestCards() {
    }

    public static Optional<TestCard> find(String number) {
        return Optional.ofNullable(BY_NUMBER.get(number));
    }

    public static boolean isTestCard(String number) {
        return BY_NUMBER.containsKey(number);
    }
}
