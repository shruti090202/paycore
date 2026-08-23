package com.paycore.risk;

import com.paycore.risk.rules.AmountThresholdRule;
import com.paycore.risk.rules.TestCardSignalRule;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskEngineTest {

    private static RuleConfig cfg(String type, int weight, boolean enabled, Map<String, Object> params) {
        return new RuleConfig("r_" + type, null, type, type, params, weight, enabled);
    }

    private static RiskContext ctx(long amount, String card, String email) {
        return new RiskContext("mer_1", "pay_1", amount, "INR", "fp_" + card, card, email, null);
    }

    private static final RiskRule ALWAYS_UNKNOWN = new RiskRule() {
        @Override public String type() { return "flaky"; }
        @Override public Result evaluate(RiskContext c, RuleConfig cfg) { return Result.unknown("store down"); }
    };

    private static final RiskRule THROWS = new RiskRule() {
        @Override public String type() { return "buggy"; }
        @Override public Result evaluate(RiskContext c, RuleConfig cfg) { throw new IllegalStateException("boom"); }
    };

    @Test
    void amountThresholds() {
        AmountThresholdRule rule = new AmountThresholdRule();
        RuleConfig c = cfg("amount_threshold", 40, true, Map.of("review_above_minor", 1_000_000, "block_above_minor", 10_000_000));
        assertThat(rule.evaluate(ctx(999_999, "4242", null), c).fired()).isFalse();
        assertThat(rule.evaluate(ctx(1_000_000, "4242", null), c).fired()).as("threshold is exclusive").isFalse();
        assertThat(rule.evaluate(ctx(1_000_001, "4242", null), c).score()).isEqualTo(40);
        assertThat(rule.evaluate(ctx(10_000_001, "4242", null), c).score()).isEqualTo(100);
        assertThat(rule.evaluate(ctx(10_000_001, "4242", null), c).reason()).contains("block threshold");
        assertThat(rule.evaluate(ctx(1, "4242", null), cfg("amount_threshold", 40, true, Map.of())).fired())
                .as("missing params never fire").isFalse();
    }

    @Test
    void testCardSignals() {
        TestCardSignalRule rule = new TestCardSignalRule();
        RuleConfig c = cfg("test_card_signal", 50, true, Map.of());
        assertThat(rule.evaluate(ctx(1, "4242424242424242", null), c).fired()).isFalse();
        assertThat(rule.evaluate(ctx(1, TestCardSignalRule.REVIEW_CARD, null), c).score()).isEqualTo(50);
        assertThat(rule.evaluate(ctx(1, TestCardSignalRule.BLOCK_CARD, null), c).score()).isEqualTo(100);
    }

    @Test
    void scoresAddUpAreCappedAndMapToDecisions() {
        RiskEngine engine = new RiskEngine(List.of(new AmountThresholdRule(), new TestCardSignalRule()),
                m -> List.of(
                        cfg("amount_threshold", 40, true, Map.of("review_above_minor", 100, "block_above_minor", 1_000_000)),
                        cfg("test_card_signal", 50, true, Map.of())),
                Clock.systemUTC());

        RiskDecision allow = engine.evaluate(ctx(50, "4242424242424242", null));
        assertThat(allow.score()).isZero();
        assertThat(allow.outcome()).isEqualTo(RiskDecision.Outcome.ALLOW);
        assertThat(allow.reasons()).isEmpty();

        RiskDecision review = engine.evaluate(ctx(500, "4242424242424242", null));
        assertThat(review.score()).isEqualTo(40);
        assertThat(review.outcome()).isEqualTo(RiskDecision.Outcome.REVIEW);
        assertThat(review.reasons()).extracting(RiskDecision.Reason::rule).containsExactly("amount_threshold");

        RiskDecision both = engine.evaluate(ctx(500, TestCardSignalRule.REVIEW_CARD, null));
        assertThat(both.score()).isEqualTo(90);
        assertThat(both.outcome()).isEqualTo(RiskDecision.Outcome.BLOCK);
        assertThat(both.reasons()).hasSize(2);

        RiskDecision capped = engine.evaluate(ctx(5_000_000, TestCardSignalRule.BLOCK_CARD, null));
        assertThat(capped.score()).as("never above 100").isEqualTo(100);
    }

    @Test
    void disabledRulesAreSkippedAndUnknownAddsASmallPenalty() {
        RiskEngine engine = new RiskEngine(List.of(new AmountThresholdRule(), ALWAYS_UNKNOWN, THROWS),
                m -> List.of(
                        cfg("amount_threshold", 40, false, Map.of("review_above_minor", 1)),
                        cfg("flaky", 50, true, Map.of()),
                        cfg("buggy", 50, true, Map.of())),
                Clock.systemUTC());
        RiskDecision d = engine.evaluate(ctx(1_000, "4242424242424242", "a@b.c"));
        assertThat(d.score()).as("two unknowns: 10 + 10, disabled amount rule ignored").isEqualTo(20);
        assertThat(d.outcome()).isEqualTo(RiskDecision.Outcome.ALLOW);
        assertThat(d.reasons()).extracting(RiskDecision.Reason::message).contains("store down", "Rule failed to evaluate");
    }

    @Test
    void decisionThresholds() {
        assertThat(RiskDecision.outcomeFor(39)).isEqualTo(RiskDecision.Outcome.ALLOW);
        assertThat(RiskDecision.outcomeFor(40)).isEqualTo(RiskDecision.Outcome.REVIEW);
        assertThat(RiskDecision.outcomeFor(79)).isEqualTo(RiskDecision.Outcome.REVIEW);
        assertThat(RiskDecision.outcomeFor(80)).isEqualTo(RiskDecision.Outcome.BLOCK);
    }

    @Test
    void emailHashIsNormalizedAndNotReversible() {
        assertThat(BlocklistRepository.hashEmail("Fraud@Example.com ")).isEqualTo(BlocklistRepository.hashEmail("fraud@example.com"));
        assertThat(BlocklistRepository.hashEmail("fraud@example.com")).startsWith("eh_").doesNotContain("fraud");
    }
}
