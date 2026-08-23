package com.paycore.risk.rules;

import com.paycore.risk.BlocklistRepository;
import com.paycore.risk.RiskContext;
import com.paycore.risk.RiskRule;
import com.paycore.risk.RuleConfig;
import org.springframework.stereotype.Component;

/** Card fingerprints and emails a merchant (or the platform) has explicitly banned. Always a hard block. */
@Component
public class BlocklistRule implements RiskRule {

    public static final String TYPE = "blocklist";

    private final BlocklistRepository blocklist;

    public BlocklistRule(BlocklistRepository blocklist) {
        this.blocklist = blocklist;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Result evaluate(RiskContext ctx, RuleConfig config) {
        if (ctx.cardFingerprint() != null && blocklist.isBlocked(ctx.merchantId(), "card_fingerprint", ctx.cardFingerprint())) {
            return Result.fired(100, "Card is on the blocklist");
        }
        if (ctx.customerEmail() != null && blocklist.isBlocked(ctx.merchantId(), "email", BlocklistRepository.hashEmail(ctx.customerEmail()))) {
            return Result.fired(100, "Customer email is on the blocklist");
        }
        return Result.NONE;
    }
}
