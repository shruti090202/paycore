package com.paycore.risk.rules;

import com.paycore.risk.RiskContext;
import com.paycore.risk.RiskRule;
import com.paycore.risk.RuleConfig;
import com.paycore.risk.VelocityCounters;
import org.springframework.stereotype.Component;

/** One customer cycling through many cards = stolen-card list. Params: window_seconds, max_cards. */
@Component
public class CustomerDistinctCardsRule implements RiskRule {

    public static final String TYPE = "customer_distinct_cards";

    private final VelocityCounters counters;

    public CustomerDistinctCardsRule(VelocityCounters counters) {
        this.counters = counters;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Result evaluate(RiskContext ctx, RuleConfig config) {
        if (ctx.cardFingerprint() == null || ctx.customerEmail() == null || ctx.customerEmail().isBlank()) {
            return Result.NONE;
        }
        long window = config.paramLong("window_seconds", 3600);
        long max = config.paramLong("max_cards", 3);
        Long cards = counters.recordCustomerCard(ctx.merchantId(), ctx.customerEmail(), ctx.cardFingerprint(), window);
        if (cards == null) {
            return Result.unknown("Distinct-card check could not run (counter store unavailable)");
        }
        if (cards > max) {
            return Result.fired(config.weight(), cards + " different cards used by this customer in " + window + "s (max " + max + ")");
        }
        return Result.NONE;
    }
}
