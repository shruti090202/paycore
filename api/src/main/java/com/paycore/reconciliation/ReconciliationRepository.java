package com.paycore.reconciliation;

import com.paycore.common.id.Ids;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class ReconciliationRepository {

    public record SettlementFile(String id, LocalDate settlementDate, int rowCount, long totalMinor, String currency,
                                 String csv, Instant generatedAt) {
    }

    public record Run(String id, String settlementFileId, String status, int rowsTotal, int rowsMatched, int itemsOpen,
                      long settledMinor, String journalEntryId, Instant startedAt, Instant finishedAt, String error) {
    }

    public record Item(String id, String runId, String merchantId, String kind, String bankRef, String paymentId,
                       String refundId, Long expectedMinor, Long actualMinor, String detail, String status,
                       String resolution, Instant resolvedAt, Instant createdAt) {
    }

    public record Payout(String id, String merchantId, long amountMinor, String currency, String status,
                         String journalEntryId, Instant createdAt) {
    }

    private final JdbcClient jdbc;

    public ReconciliationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- settlement files ---------------------------------------------------------------------------------

    public SettlementFile storeFile(LocalDate date, String currency, String csv, int rows, long total, Instant now) {
        String id = Ids.newId("stl");
        jdbc.sql("""
                INSERT INTO settlement_files (id, settlement_date, row_count, total_minor, currency, csv, generated_at)
                VALUES (:id, :d, :rows, :total, :ccy, :csv, :now)
                """).param("id", id).param("d", date).param("rows", rows).param("total", total).param("ccy", currency)
                .param("csv", csv).param("now", now.atOffset(ZoneOffset.UTC)).update();
        return file(id).orElseThrow();
    }

    public Optional<SettlementFile> file(String id) {
        return jdbc.sql("SELECT * FROM settlement_files WHERE id = :id").param("id", id).query(this::mapFile).optional();
    }

    public List<SettlementFile> filesWithoutRun() {
        return jdbc.sql("SELECT f.* FROM settlement_files f LEFT JOIN reconciliation_runs r ON r.settlement_file_id = f.id WHERE r.id IS NULL ORDER BY f.id")
                .query(this::mapFile).list();
    }

    public List<SettlementFile> files(int limit) {
        return jdbc.sql("SELECT * FROM settlement_files ORDER BY id DESC LIMIT :l").param("l", limit).query(this::mapFile).list();
    }

    private SettlementFile mapFile(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new SettlementFile(rs.getString("id"), rs.getDate("settlement_date").toLocalDate(), rs.getInt("row_count"),
                rs.getLong("total_minor"), rs.getString("currency").trim(), rs.getString("csv"), rs.getTimestamp("generated_at").toInstant());
    }

    // ---- runs ----------------------------------------------------------------------------------------------

    public String startRun(String fileId, Instant now) {
        String id = Ids.newId("rcn");
        jdbc.sql("INSERT INTO reconciliation_runs (id, settlement_file_id, status, started_at) VALUES (:id, :f, 'running', :now)")
                .param("id", id).param("f", fileId).param("now", now.atOffset(ZoneOffset.UTC)).update();
        return id;
    }

    public void finishRun(String id, int total, int matched, int open, long settled, String journalEntryId, Instant now) {
        jdbc.sql("""
                UPDATE reconciliation_runs SET status = 'completed', rows_total = :t, rows_matched = :m, items_open = :o,
                       settled_minor = :s, journal_entry_id = :je, finished_at = :now WHERE id = :id
                """).param("t", total).param("m", matched).param("o", open).param("s", settled).param("je", journalEntryId)
                .param("now", now.atOffset(ZoneOffset.UTC)).param("id", id).update();
    }

    public void failRun(String id, String error, Instant now) {
        jdbc.sql("UPDATE reconciliation_runs SET status = 'failed', error = :e, finished_at = :now WHERE id = :id")
                .param("e", error).param("now", now.atOffset(ZoneOffset.UTC)).param("id", id).update();
    }

    public List<Run> runs(int limit) {
        return jdbc.sql("SELECT * FROM reconciliation_runs ORDER BY id DESC LIMIT :l").param("l", limit).query(this::mapRun).list();
    }

    public Optional<Run> run(String id) {
        return jdbc.sql("SELECT * FROM reconciliation_runs WHERE id = :id").param("id", id).query(this::mapRun).optional();
    }

    private Run mapRun(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        java.sql.Timestamp fin = rs.getTimestamp("finished_at");
        return new Run(rs.getString("id"), rs.getString("settlement_file_id"), rs.getString("status"), rs.getInt("rows_total"),
                rs.getInt("rows_matched"), rs.getInt("items_open"), rs.getLong("settled_minor"), rs.getString("journal_entry_id"),
                rs.getTimestamp("started_at").toInstant(), fin == null ? null : fin.toInstant(), rs.getString("error"));
    }

    // ---- items ---------------------------------------------------------------------------------------------

    public void addItem(String runId, String merchantId, String kind, String bankRef, String paymentId, String refundId,
                        Long expected, Long actual, String detail, Instant now) {
        jdbc.sql("""
                INSERT INTO reconciliation_items (id, run_id, merchant_id, kind, bank_ref, payment_id, refund_id, expected_minor, actual_minor, detail, status, created_at)
                VALUES (:id, :run, :m, :kind, :ref, :p, :r, :exp, :act, :detail, 'open', :now)
                """).param("id", Ids.newId("rci")).param("run", runId).param("m", merchantId).param("kind", kind).param("ref", bankRef)
                .param("p", paymentId).param("r", refundId).param("exp", expected).param("act", actual).param("detail", detail)
                .param("now", now.atOffset(ZoneOffset.UTC)).update();
    }

    public List<Item> itemsForRun(String runId) {
        return jdbc.sql("SELECT * FROM reconciliation_items WHERE run_id = :r ORDER BY id").param("r", runId).query(this::item).list();
    }

    public List<Item> itemsForMerchant(String merchantId, String status, int limit) {
        String sql = "SELECT * FROM reconciliation_items WHERE merchant_id = :m" + (status == null ? "" : " AND status = :s") + " ORDER BY id DESC LIMIT :l";
        var spec = jdbc.sql(sql).param("m", merchantId).param("l", limit);
        if (status != null) {
            spec = spec.param("s", status);
        }
        return spec.query(this::item).list();
    }

    public int resolveItem(String id, String merchantId, String resolution, Instant now) {
        return jdbc.sql("UPDATE reconciliation_items SET status = 'resolved', resolution = :res, resolved_at = :now WHERE id = :id AND merchant_id = :m AND status = 'open'")
                .param("res", resolution).param("now", now.atOffset(ZoneOffset.UTC)).param("id", id).param("m", merchantId).update();
    }

    private Item item(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        java.sql.Timestamp res = rs.getTimestamp("resolved_at");
        return new Item(rs.getString("id"), rs.getString("run_id"), rs.getString("merchant_id"), rs.getString("kind"), rs.getString("bank_ref"),
                rs.getString("payment_id"), rs.getString("refund_id"), (Long) rs.getObject("expected_minor"), (Long) rs.getObject("actual_minor"),
                rs.getString("detail"), rs.getString("status"), rs.getString("resolution"), res == null ? null : res.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    // ---- bank attempts (gateway side) ----------------------------------------------------------------------

    public record Attempt(String id, String paymentId, String refundId, String kind, String bankRef, long amountMinor,
                          String currency, String outcome, String resolution, String merchantId, String paymentStatus,
                          long capturedMinor, String refundStatus, Instant createdAt) {
        public boolean approved() {
            return "approved".equals(outcome) || "approved".equals(resolution);
        }
    }

    public Optional<Attempt> attemptByBankRef(String bankRef) {
        return jdbc.sql(ATTEMPT_SQL + " WHERE a.bank_ref = :ref").param("ref", bankRef).query(this::attempt).optional();
    }

    /** Approved calls the bank should have reported by now but never did. */
    public List<Attempt> unsettledApprovedBefore(Instant cutoff, String currency) {
        return jdbc.sql(ATTEMPT_SQL + """
                 WHERE a.settled_in IS NULL AND a.currency = :ccy AND a.created_at < :cutoff
                   AND (a.outcome = 'approved' OR a.resolution = 'approved')
                 ORDER BY a.id
                """).param("ccy", currency).param("cutoff", cutoff.atOffset(ZoneOffset.UTC)).query(this::attempt).list();
    }

    public void markSettled(String attemptId, String fileId) {
        jdbc.sql("UPDATE bank_attempts SET settled_in = :f WHERE id = :id AND settled_in IS NULL").param("f", fileId).param("id", attemptId).update();
    }

    private static final String ATTEMPT_SQL = """
            SELECT a.id, a.payment_id, a.refund_id, a.kind, a.bank_ref, a.amount_minor, a.currency, a.outcome, a.resolution, a.created_at,
                   p.merchant_id, p.status AS payment_status, p.captured_minor, r.status AS refund_status
              FROM bank_attempts a
              JOIN payments p ON p.id = a.payment_id
              LEFT JOIN refunds r ON r.id = a.refund_id
            """;

    private Attempt attempt(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Attempt(rs.getString("id"), rs.getString("payment_id"), rs.getString("refund_id"), rs.getString("kind"),
                rs.getString("bank_ref"), rs.getLong("amount_minor"), rs.getString("currency").trim(), rs.getString("outcome"),
                rs.getString("resolution"), rs.getString("merchant_id"), rs.getString("payment_status"), rs.getLong("captured_minor"),
                rs.getString("refund_status"), rs.getTimestamp("created_at").toInstant());
    }

    // ---- payouts -------------------------------------------------------------------------------------------

    public record MerchantBalance(String merchantId, String currency, long balanceMinor) {
    }

    public List<MerchantBalance> positiveMerchantBalances() {
        return jdbc.sql("""
                SELECT merchant_id, currency, balance_minor FROM account_balances
                 WHERE merchant_id IS NOT NULL AND code LIKE 'merchant_payable:%' AND balance_minor > 0
                 ORDER BY merchant_id
                """).query((rs, i) -> new MerchantBalance(rs.getString(1), rs.getString(2).trim(), rs.getLong(3))).list();
    }

    public void insertPayout(String id, String merchantId, long amount, String currency, String journalEntryId, Instant now) {
        jdbc.sql("INSERT INTO payouts (id, merchant_id, amount_minor, currency, status, journal_entry_id, created_at) VALUES (:id, :m, :a, :c, 'paid', :je, :now)")
                .param("id", id).param("m", merchantId).param("a", amount).param("c", currency).param("je", journalEntryId)
                .param("now", now.atOffset(ZoneOffset.UTC)).update();
    }

    public List<Payout> payouts(String merchantId, int limit) {
        return jdbc.sql("SELECT * FROM payouts WHERE merchant_id = :m ORDER BY id DESC LIMIT :l").param("m", merchantId).param("l", limit)
                .query((rs, i) -> new Payout(rs.getString("id"), rs.getString("merchant_id"), rs.getLong("amount_minor"),
                        rs.getString("currency").trim(), rs.getString("status"), rs.getString("journal_entry_id"),
                        rs.getTimestamp("created_at").toInstant())).list();
    }
}
