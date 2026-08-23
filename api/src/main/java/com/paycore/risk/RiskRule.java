package com.paycore.risk;

/**
 * One signal. A rule returns how many points it adds and a human-readable reason (or nothing).
 * {@code unknown} means the rule could not evaluate (e.g. Redis down): the engine adds a small penalty
 * rather than silently allowing, and the reason says so.
 */
public interface RiskRule {

    String type();

    Result evaluate(RiskContext ctx, RuleConfig config);

    record Result(int score, String reason, boolean unknown) {
        public static final Result NONE = new Result(0, null, false);

        public static Result fired(int score, String reason) {
            return new Result(score, reason, false);
        }

        public static Result unknown(String reason) {
            return new Result(0, reason, true);
        }

        public boolean fired() {
            return score > 0 || unknown;
        }
    }
}
