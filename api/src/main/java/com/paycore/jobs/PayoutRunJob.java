package com.paycore.jobs;

import com.paycore.reconciliation.PayoutService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class PayoutRunJob implements Job {

    public static final String NAME = "payout-run";

    private final PayoutService payouts;

    public PayoutRunJob(PayoutService payouts) {
        this.payouts = payouts;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Map<String, Object> run() {
        List<PayoutService.Paid> paid = payouts.runPayouts();
        long total = paid.stream().mapToLong(PayoutService.Paid::amountMinor).sum();
        return Map.of("payouts", paid.size(), "total_minor", total,
                "details", paid.stream().map(p -> Map.of("id", p.payoutId(), "merchant_id", p.merchantId(), "amount_minor", p.amountMinor())).toList());
    }
}
