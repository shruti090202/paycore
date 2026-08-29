package com.paycore.reconciliation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

/** Gateway side of settlement: store the file exactly as received. Parsing and matching happen in the run. */
@Service
public class SettlementIngestService {

    private final ReconciliationRepository repo;
    private final Clock clock;

    public SettlementIngestService(ReconciliationRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    @Transactional
    public ReconciliationRepository.SettlementFile ingest(LocalDate date, String currency, String csv, int rows, long netMinor) {
        return repo.storeFile(date, currency, csv, rows, netMinor, clock.instant());
    }
}
