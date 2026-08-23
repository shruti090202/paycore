package com.paycore.risk.rules;

import com.paycore.risk.RiskContext;
import com.paycore.risk.RiskRule;
import com.paycore.risk.RuleConfig;
import com.paycore.risk.VelocityCounters;
import org.springframework.stereotype.Component;

/** Many attempts with one card in a short window = card testing. Params: window_seconds, max_attempts. */
@Component
public class CardVelocityRule implements RiskRule {

    public static final String TYPE = "card_velocity";

    private final VelocityCounters counters;

    public CardVelocityRule(VelocityCounters counters) {
        this.counters = counters;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Result evaluate(RiskContext ctx, RuleConfig config) {
        if (ctx.cardFingerprint() == null) {
            return Result.NONE;
        }
        long window = config.paramLong("window_seconds", 60);
        long max = config.paramLong("max_attempts", 5);
        Long attempts = counters.incrementCardAttempts(ctx.merchantId(), ctx.cardFingerprint(), window);
        if (attempts == null) {
            return Result.unknown("Card velocity could not be checked (counter store unavailable)");
        }
        if (attempts > max) {
            return Result.fired(config.weight(), attempts + " attempts with this card in " + window + "s (max " + max + ")");
        }
        return Result.NONE;
    }
}
