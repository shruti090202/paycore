package com.paycore.risk.rules;

import com.paycore.risk.RiskContext;
import com.paycore.risk.RiskRule;
import com.paycore.risk.RuleConfig;
import org.springframework.stereotype.Component;

/**
 * Stands in for issuer/network fraud signals a real gateway would receive. Two documented test cards
 * trigger it: 4000000000009235 (elevated risk -> review) and 4100000000000019 (known fraud -> block).
 */
@Component
public class TestCardSignalRule implements RiskRule {

    public static final String TYPE = "test_card_signal";
    public static final String REVIEW_CARD = "4000000000009235";
    public static final String BLOCK_CARD = "4100000000000019";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Result evaluate(RiskContext ctx, RuleConfig config) {
        if (BLOCK_CARD.equals(ctx.cardNumber())) {
            return Result.fired(100, "Issuer reports this card as fraudulent");
        }
        if (REVIEW_CARD.equals(ctx.cardNumber())) {
            return Result.fired(config.weight(), "Issuer reports elevated risk for this card");
        }
        return Result.NONE;
    }
}
