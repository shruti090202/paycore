package com.paycore.risk;

import java.util.List;

/** The engine's verdict for one payment. Score 0-100; block >= 80, review >= 40, else allow. */
public record RiskDecision(int score, Outcome outcome, List<Reason> reasons) {

    public enum Outcome {
        ALLOW, REVIEW, BLOCK;

        public String wire() {
            return name().toLowerCase();
        }
    }

    public record Reason(String rule, int score, String message) {
    }

    public static final int REVIEW_AT = 40;
    public static final int BLOCK_AT = 80;

    public static Outcome outcomeFor(int score) {
        if (score >= BLOCK_AT) return Outcome.BLOCK;
        if (score >= REVIEW_AT) return Outcome.REVIEW;
        return Outcome.ALLOW;
    }
}
