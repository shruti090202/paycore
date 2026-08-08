package com.paycore.jobs;

import com.paycore.payments.BankResolutionService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Resolves payments in {@code pending_bank} and refunds in {@code pending} by asking the bank. */
@Component
public class BankStatusCheckJob implements Job {

    public static final String NAME = "bank-status-check";
    private static final int BATCH = 200;

    private final BankResolutionService resolution;

    public BankStatusCheckJob(BankResolutionService resolution) {
        this.resolution = resolution;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Map<String, Object> run() {
        BankResolutionService.Summary p = resolution.resolvePendingPayments(BATCH);
        BankResolutionService.Summary r = resolution.resolvePendingRefunds(BATCH);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("payments", Map.of("scanned", p.scanned(), "approved", p.approved(), "declined", p.declined(),
                "not_found", p.notFound(), "still_pending", p.stillPending()));
        out.put("refunds", Map.of("scanned", r.scanned(), "approved", r.approved(), "declined", r.declined(),
                "not_found", r.notFound(), "still_pending", r.stillPending()));
        return out;
    }
}
