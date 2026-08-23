package com.paycore.risk.rules;

import com.paycore.risk.RiskContext;
import com.paycore.risk.RiskRule;
import com.paycore.risk.RuleConfig;
import org.springframework.stereotype.Component;

/** Large payments deserve a look; absurd ones are refused outright. Params: review_above_minor, block_above_minor. */
@Component
public class AmountThresholdRule implements RiskRule {

    public static final String TYPE = "amount_threshold";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Result evaluate(RiskContext ctx, RuleConfig config) {
        long block = config.paramLong("block_above_minor", Long.MAX_VALUE);
        long review = config.paramLong("review_above_minor", Long.MAX_VALUE);
        if (ctx.amountMinor() > block) {
            return Result.fired(100, "Amount " + ctx.amountMinor() + " exceeds block threshold " + block);
        }
        if (ctx.amountMinor() > review) {
            return Result.fired(config.weight(), "Amount " + ctx.amountMinor() + " exceeds review threshold " + review);
        }
        return Result.NONE;
    }
}
