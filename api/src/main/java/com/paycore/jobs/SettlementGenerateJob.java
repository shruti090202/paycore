package com.paycore.jobs;

import com.paycore.banksim.SettlementGenerator;
import com.paycore.reconciliation.SettlementIngestService;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** "The bank sends us today's settlement file." The simulator produces it; the gateway stores it verbatim. */
@Component
public class SettlementGenerateJob implements Job {

    public static final String NAME = "settlement-generate";

    private final SettlementGenerator generator;
    private final SettlementIngestService ingest;
    private final Clock clock;

    public SettlementGenerateJob(SettlementGenerator generator, SettlementIngestService ingest, Clock clock) {
        this.generator = generator;
        this.ingest = ingest;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Map<String, Object> run() {
        List<Map<String, Object>> files = new ArrayList<>();
        for (SettlementGenerator.File f : generator.generate(clock.instant())) {
            var stored = ingest.ingest(f.date(), f.currency(), f.csv(), f.rows(), f.netMinor());
            files.add(Map.of("id", stored.id(), "currency", f.currency(), "rows", f.rows(), "net_minor", f.netMinor()));
        }
        return Map.of("files", files);
    }
}
