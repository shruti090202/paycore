package com.paycore.reconciliation;

import com.paycore.common.money.Money;
import com.paycore.ledger.AccountCodes;
import com.paycore.ledger.AccountType;
import com.paycore.ledger.JournalEntry;
import com.paycore.ledger.LedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Matches a settlement file (the bank's claim) against our books (bank_attempts + payments + refunds) and records every discrepancy as an item for a. */
@Service
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    public record CsvRow(String bankRef, String kind, String parentRef, long amountMinor, String currency, int line) {
    }

    public record Summary(String runId, int rowsTotal, int rowsMatched, int itemsOpen, long settledMinor) {
    }

    private final ReconciliationRepository repo;
    private final LedgerService ledger;
    private final TransactionTemplate tx;
    private final Clock clock;

    public Reconciler(ReconciliationRepository repo, LedgerService ledger, PlatformTransactionManager txManager, Clock clock) {
        this.repo = repo;
        this.ledger = ledger;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /** Reconcile every stored file that has no run yet. */
    public List<Summary> reconcilePending() {
        List<Summary> out = new ArrayList<>();
        for (ReconciliationRepository.SettlementFile f : repo.filesWithoutRun()) {
            out.add(reconcile(f));
        }
        return out;
    }

    public Summary reconcile(ReconciliationRepository.SettlementFile file) {
        Instant now = clock.instant();
        String runId = tx.execute(s -> repo.startRun(file.id(), now));
        try {
            Summary summary = tx.execute(s -> doReconcile(runId, file, now));
            log.info("reconciliation {} for {}: {}", runId, file.id(), summary);
            return summary;
        } catch (RuntimeException e) {
            log.error("reconciliation {} failed", runId, e);
            tx.executeWithoutResult(s -> repo.failRun(runId, e.getClass().getSimpleName() + ": " + e.getMessage(), clock.instant()));
            throw e;
        }
    }

    private Summary doReconcile(String runId, ReconciliationRepository.SettlementFile file, Instant now) {
        List<CsvRow> rows = parse(file.csv());
        Set<String> seen = new HashSet<>();
        int matched = 0, open = 0;
        long settledAuth = 0, settledRefund = 0;

        for (CsvRow row : rows) {
            if (!seen.add(row.bankRef())) {
                repo.addItem(runId, merchantOf(row.bankRef()), "duplicate", row.bankRef(), null, null, null, row.amountMinor(),
                        "bank_ref appears more than once in the settlement file (line " + row.line() + ")", now);
                open++;
                continue;
            }
            Optional<ReconciliationRepository.Attempt> found = repo.attemptByBankRef(row.bankRef());
            if (found.isEmpty()) {
                repo.addItem(runId, null, "missing_in_ledger", row.bankRef(), null, null, null, row.amountMinor(),
                        "bank settled " + row.kind() + " " + row.bankRef() + " but we have no record of that call", now);
                open++;
                continue;
            }
            ReconciliationRepository.Attempt a = found.get();
            repo.markSettled(a.id(), file.id()); // reported by the bank, matched or not: never "missing in bank" later
            if (!a.approved()) {
                repo.addItem(runId, a.merchantId(), "missing_in_ledger", row.bankRef(), a.paymentId(), a.refundId(), 0L, row.amountMinor(),
                        "bank settled a call we recorded as " + a.outcome(), now);
                open++;
                continue;
            }
            if (row.amountMinor() != a.amountMinor()) {
                repo.addItem(runId, a.merchantId(), "amount_mismatch", row.bankRef(), a.paymentId(), a.refundId(), a.amountMinor(), row.amountMinor(),
                        "bank amount differs from the amount we sent", now);
                open++;
                continue;
            }
            if ("authorize".equals(a.kind())) {
                boolean captured = switch (a.paymentStatus()) {
                    case "captured", "partially_refunded", "refunded" -> true;
                    default -> false;
                };
                if (!captured) {
                    repo.addItem(runId, a.merchantId(), "missing_in_ledger", row.bankRef(), a.paymentId(), null, 0L, row.amountMinor(),
                            "bank settled an authorization for a payment that is " + a.paymentStatus() + " (never captured)", now);
                    open++;
                    continue;
                }
                if (a.capturedMinor() != row.amountMinor()) {
                    repo.addItem(runId, a.merchantId(), "amount_mismatch", row.bankRef(), a.paymentId(), null, a.capturedMinor(), row.amountMinor(),
                            "bank settled the authorized amount but we captured " + a.capturedMinor(), now);
                    open++;
                    continue;
                }
                settledAuth += row.amountMinor();
            } else {
                if (!"succeeded".equals(a.refundStatus())) {
                    repo.addItem(runId, a.merchantId(), "missing_in_ledger", row.bankRef(), a.paymentId(), a.refundId(), 0L, row.amountMinor(),
                            "bank settled a refund that is " + a.refundStatus() + " in our books", now);
                    open++;
                    continue;
                }
                settledRefund += row.amountMinor();
            }
            matched++;
        }

        // Our side: approved calls older than this file that no file has ever reported.
        for (ReconciliationRepository.Attempt a : repo.unsettledApprovedBefore(file.generatedAt(), file.currency())) {
            boolean relevant = "authorize".equals(a.kind())
                    ? (a.capturedMinor() > 0)
                    : "succeeded".equals(a.refundStatus());
            if (!relevant) {
                continue;
            }
            repo.addItem(runId, a.merchantId(), "missing_in_bank", a.bankRef(), a.paymentId(), a.refundId(),
                    "authorize".equals(a.kind()) ? a.capturedMinor() : a.amountMinor(), null,
                    "we recorded an approved " + a.kind() + " that the bank has not reported", now);
            repo.markSettled(a.id(), file.id()); // reported once as an exception, not on every future run
            open++;
        }

        long net = settledAuth - settledRefund;
        String journalEntryId = null;
        if (net != 0) {
            Money amount = Money.of(Math.abs(net), file.currency());
            List<LedgerService.Leg> legs = net > 0
                    ? List.of(LedgerService.Leg.debit(AccountCodes.settlementCash(file.currency()), AccountType.ASSET, null, amount),
                              LedgerService.Leg.credit(AccountCodes.bankReceivable(file.currency()), AccountType.ASSET, null, amount))
                    : List.of(LedgerService.Leg.debit(AccountCodes.bankReceivable(file.currency()), AccountType.ASSET, null, amount),
                              LedgerService.Leg.credit(AccountCodes.settlementCash(file.currency()), AccountType.ASSET, null, amount));
            JournalEntry je = ledger.post(LedgerService.KIND_SETTLEMENT, "settlement", file.id(),
                    "Settlement " + file.settlementDate() + " " + file.currency(), legs);
            journalEntryId = je.id();
        }
        repo.finishRun(runId, rows.size(), matched, open, net, journalEntryId, clock.instant());
        return new Summary(runId, rows.size(), matched, open, net);
    }

    private String merchantOf(String bankRef) {
        return repo.attemptByBankRef(bankRef).map(ReconciliationRepository.Attempt::merchantId).orElse(null);
    }

    /** Strict parser for the simulator's CSV (no quoting needed: no field can contain a comma). */
    static List<CsvRow> parse(String csv) {
        List<CsvRow> rows = new ArrayList<>();
        String[] lines = csv.split("\r?\n");
        if (lines.length == 0 || !lines[0].startsWith("bank_ref,")) {
            throw new IllegalArgumentException("settlement file has no header");
        }
        Map<String, Integer> col = new LinkedHashMap<>();
        String[] header = lines[0].split(",");
        for (int i = 0; i < header.length; i++) {
            col.put(header[i].trim(), i);
        }
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            String[] f = lines[i].split(",", -1);
            try {
                rows.add(new CsvRow(f[col.get("bank_ref")], f[col.get("kind")], f[col.get("parent_ref")].isEmpty() ? null : f[col.get("parent_ref")],
                        Long.parseLong(f[col.get("amount_minor")]), f[col.get("currency")], i + 1));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("settlement file line " + (i + 1) + " is malformed: " + e.getMessage());
            }
        }
        return rows;
    }
}
