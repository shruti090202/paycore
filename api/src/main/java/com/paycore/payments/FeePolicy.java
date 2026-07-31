package com.paycore.payments;

import com.paycore.common.money.Money;

/**
 * Gateway pricing: {@code percent (bps) + fixed}, capped at the captured amount so a tiny capture can never
 * produce a negative merchant payable. Both inputs come from the merchant row, so pricing is data.
 */
public final class FeePolicy {

    private FeePolicy() {
    }

    public static Money feeFor(Money captured, int feeBps, long feeFixedMinor) {
        Money percent = captured.percentBps(feeBps);
        Money fixed = Money.of(feeFixedMinor, captured.currency());
        return percent.plus(fixed).min(captured);
    }
}
