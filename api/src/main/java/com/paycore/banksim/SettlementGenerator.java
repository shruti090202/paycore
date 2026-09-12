package com.paycore.banksim;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** The bank's end-of-day settlement report: every approved transaction it has not reported yet, as CSV. */
@Component
public class SettlementGenerator {

    public static final String HEADER = "bank_ref,kind,parent_ref,amount_minor,currency,card_last4,processed_at";

    public record File(LocalDate date, String currency, String csv, int rows, long netMinor) {
    }

    private record Row(String bankRef, String kind, String parentRef, long amount, String currency, String last4, Instant at) {
        String csv() {
            return String.join(",", bankRef, kind, parentRef == null ? "" : parentRef, String.valueOf(amount), currency,
                    last4 == null ? "" : last4, at.toString());
        }
    }

    private final JdbcClient jdbc;
    private final BankSimConfig config;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public SettlementGenerator(JdbcClient jdbc, BankSimConfig config, Clock clock) {
        this.jdbc = jdbc;
        this.config = config;
        this.clock = clock;
    }

    /** One file per currency with unsettled approved transactions processed before {@code cutoff}. */
    @Transactional
    public List<File> generate(Instant cutoff) {
        LocalDate date = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<String> currencies = jdbc.sql("SELECT DISTINCT currency FROM banksim_transactions WHERE outcome = 'approved' AND settled_on IS NULL AND created_at < :cutoff")
                .param("cutoff", cutoff.atOffset(ZoneOffset.UTC)).query(String.class).list();
        List<File> files = new ArrayList<>();
        for (String ccy : currencies) {
            List<Row> rows = jdbc.sql("""
                    SELECT bank_ref, kind, parent_ref, amount_minor, currency, card_last4, created_at
                      FROM banksim_transactions
                     WHERE outcome = 'approved' AND settled_on IS NULL AND currency = :ccy AND created_at < :cutoff
                     ORDER BY created_at, bank_ref
                    """).param("ccy", ccy).param("cutoff", cutoff.atOffset(ZoneOffset.UTC))
                    .query((rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                            rs.getString(5).trim(), rs.getString(6), rs.getTimestamp(7).toInstant())).list();
            if (rows.isEmpty()) {
                continue;
            }
            jdbc.sql("UPDATE banksim_transactions SET settled_on = :d WHERE outcome = 'approved' AND settled_on IS NULL AND currency = :ccy AND created_at < :cutoff")
                    .param("d", date).param("ccy", ccy).param("cutoff", cutoff.atOffset(ZoneOffset.UTC)).update();

            List<Row> reported = applyAnomalies(rows);
            StringBuilder csv = new StringBuilder(HEADER).append('\n');
            long net = 0;
            for (Row r : reported) {
                csv.append(r.csv()).append('\n');
                net += "refund".equals(r.kind()) ? -r.amount() : r.amount();
            }
            files.add(new File(date, ccy, csv.toString(), reported.size(), net));
        }
        return files;
    }

    private List<Row> applyAnomalies(List<Row> rows) {
        double rate = config.get().settlementAnomalyRate();
        if (rate <= 0) {
            return rows;
        }
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            if (random.nextDouble() >= rate) {
                out.add(r);
                continue;
            }
            switch (random.nextInt(3)) {
                case 0 -> out.add(new Row(r.bankRef(), r.kind(), r.parentRef(), r.amount() + 1, r.currency(), r.last4(), r.at())); // amount off by one
                case 1 -> { /* dropped: the gateway will report it missing in bank */ }
                default -> { out.add(r); out.add(r); } // duplicated
            }
        }
        return out;
    }
}
