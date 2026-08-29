package com.paycore.reconciliation;

import com.paycore.ledger.LedgerRepository;
import com.paycore.support.AbstractIntegrationTest;
import com.paycore.support.Api;
import com.paycore.support.Flows;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationIT extends AbstractIntegrationTest {

    @Autowired JdbcClient jdbc;
    @Autowired LedgerRepository ledgerRepo;
    @Autowired SettlementIngestService ingest;

    private record Setup(Api api, String key, String token) {
    }

    private Setup setup() {
        Api api = api();
        String token = api.signupAndGetToken();
        String key = api.post("/dashboard/api_keys", Map.of("name", "k"), Api.bearer(token)).text("/key");
        return new Setup(api, key, token);
    }

    private String bankRefOf(String paymentId) {
        return jdbc.sql("SELECT bank_ref FROM payments WHERE id = :p").param("p", paymentId).query(String.class).single();
    }

    private long balance(Setup s) {
        return s.api().get("/v1/balance", Api.bearer(s.key())).at("/available/0/balance_minor").asLong();
    }

    @Test
    void cleanDayReconcilesWithoutExceptionsAndPaysOutSettledMoney() {
        Setup s = setup();
        String p1 = Flows.paidPayment(s.api(), s.key(), 10_000, "automatic");   // net 9500
        String p2 = Flows.paidPayment(s.api(), s.key(), 2_500, "automatic");    // net 2150
        s.api().post("/v1/payments/" + p1 + "/refunds", Map.of("amount_minor", 1_000), Api.bearer(s.key()));
        long owed = balance(s);
        assertThat(owed).isEqualTo(9_500 + 2_150 - 1_000);

        Api.Response gen = Flows.runJob(s.api(), "settlement-generate");
        assertThat(gen.status()).as(gen.raw()).isEqualTo(200);
        assertThat(gen.at("/result/files")).isNotEmpty();

        Api.Response rec = Flows.runJob(s.api(), "reconcile");
        assertThat(rec.status()).as(rec.raw()).isEqualTo(200);
        assertThat(rec.at("/result/runs")).isNotEmpty();

        // This merchant's payments all match: no exceptions for it.
        assertThat(s.api().get("/dashboard/reconciliation/items", Api.bearer(s.token())).body()).isEmpty();
        // The bank's rows for our payments are marked as reported.
        for (String id : List.of(p1, p2)) {
            assertThat(jdbc.sql("SELECT settled_in FROM bank_attempts WHERE bank_ref = :r").param("r", bankRefOf(id)).query(String.class).single())
                    .startsWith("stl_");
        }
        // The run booked matched money into settlement_cash via a balanced settlement entry.
        Api.Response runs = s.api().get("/dashboard/reconciliation/runs", Api.bearer(s.token()));
        assertThat(runs.at("/0/status").asString()).isEqualTo("completed");
        assertThat(runs.at("/0/settled_minor").asLong()).isGreaterThanOrEqualTo(10_000 + 2_500 - 1_000);
        assertThat(ledgerRepo.sumOfAllPostingsSigned()).isZero();
        assertThat(s.api().get("/dashboard/reconciliation/files", Api.bearer(s.token())).at("/0/row_count").asInt()).isGreaterThanOrEqualTo(3);
        String fileId = s.api().get("/dashboard/reconciliation/files", Api.bearer(s.token())).text("/0/id");
        assertThat(s.api().get("/dashboard/reconciliation/files/" + fileId + "/csv", Api.bearer(s.token())).raw()).startsWith("bank_ref,kind,");

        // Payouts pay what is owed, but never more than settled cash (the whole test DB shares one bank).
        long cashBefore = ledgerRepo.balanceByCode("settlement_cash:INR");
        assertThat(cashBefore).as("this run settled at least our own matched money").isGreaterThan(0);
        Api.Response pay = Flows.runJob(s.api(), "payout-run");
        assertThat(pay.status()).as(pay.raw()).isEqualTo(200);
        long paidTotal = pay.at("/result/total_minor").asLong();
        assertThat(paidTotal).isBetween(1L, cashBefore);
        assertThat(ledgerRepo.balanceByCode("settlement_cash:INR")).isEqualTo(cashBefore - paidTotal);
        long paidToUs = 0;
        for (var d : pay.at("/result/details")) {
            if (d.get("merchant_id").asString().equals(s.api().get("/v1/account", Api.bearer(s.key())).text("/id"))) {
                paidToUs = d.get("amount_minor").asLong();
            }
        }
        assertThat(paidToUs).isBetween(0L, owed);
        assertThat(balance(s)).isEqualTo(owed - paidToUs);
        Api.Response payouts = s.api().get("/dashboard/reconciliation/payouts", Api.bearer(s.token()));
        assertThat(payouts.body()).hasSize(paidToUs > 0 ? 1 : 0);
        // Running again pays nothing extra out of thin air.
        long cashAfter = ledgerRepo.balanceByCode("settlement_cash:INR");
        Api.Response pay2 = Flows.runJob(s.api(), "payout-run");
        assertThat(pay2.at("/result/total_minor").asLong()).isBetween(0L, Math.max(0, cashAfter));
        assertThat(ledgerRepo.balanceByCode("settlement_cash:INR")).isGreaterThanOrEqualTo(0);
        assertThat(ledgerRepo.sumOfAllPostingsSigned()).isZero();

        // Second settlement of the same day: nothing new to report.
        Api.Response again = Flows.runJob(s.api(), "settlement-generate");
        assertThat(again.at("/result/files")).isEmpty();
    }

    @Test
    void everyKindOfDiscrepancyIsSurfacedAndOnlyMatchedMoneyIsBooked() {
        Setup s = setup();
        String ok = Flows.paidPayment(s.api(), s.key(), 4_000, "automatic");
        String mismatch = Flows.paidPayment(s.api(), s.key(), 5_000, "automatic");
        String lost = Flows.paidPayment(s.api(), s.key(), 6_000, "automatic");
        String voided = Flows.paidPayment(s.api(), s.key(), 7_000, "manual");     // authorized at the bank...
        s.api().post("/v1/payments/" + voided + "/cancel", null, Api.bearer(s.key()));  // ...then voided by the merchant
        String partial = Flows.paidPayment(s.api(), s.key(), 8_000, "manual");
        s.api().post("/v1/payments/" + partial + "/capture", Map.of("amount_minor", 3_000), Api.bearer(s.key()));

        // Corrupt the bank's books: one amount off by one, one transaction lost entirely.
        jdbc.sql("UPDATE banksim_transactions SET amount_minor = amount_minor + 1 WHERE bank_ref = :r").param("r", bankRefOf(mismatch)).update();
        jdbc.sql("DELETE FROM banksim_transactions WHERE bank_ref = :r").param("r", bankRefOf(lost)).update();

        Flows.runJob(s.api(), "settlement-generate");
        Api.Response rec = Flows.runJob(s.api(), "reconcile");
        assertThat(rec.status()).as(rec.raw()).isEqualTo(200);

        Api.Response items = s.api().get("/dashboard/reconciliation/items", Api.bearer(s.token()));
        Map<String, String> kindByPayment = new java.util.HashMap<>();
        for (var n : items.body()) {
            if (n.hasNonNull("payment_id")) {
                kindByPayment.put(n.get("payment_id").asString(), n.get("kind").asString());
            }
        }
        assertThat(kindByPayment).doesNotContainKey(ok);
        assertThat(kindByPayment.get(mismatch)).isEqualTo("amount_mismatch");
        assertThat(kindByPayment.get(lost)).isEqualTo("missing_in_bank");
        assertThat(kindByPayment.get(voided)).isEqualTo("missing_in_ledger");
        assertThat(kindByPayment.get(partial)).isEqualTo("amount_mismatch");
        assertThat(items.raw()).contains("never captured").contains("we captured 3000");

        // Resolve one from the dashboard; it leaves the open queue.
        String itemId = items.body().findValuesAsString("id").get(0);
        Api.Response resolved = s.api().post("/dashboard/reconciliation/items/" + itemId + "/resolve",
                Map.of("resolution", "investigated with the bank"), Api.bearer(s.token()));
        assertThat(resolved.status()).isEqualTo(200);
        assertThat(resolved.body()).hasSize(3);
        assertThat(s.api().get("/dashboard/reconciliation/items?status=all", Api.bearer(s.token())).body()).hasSize(4);

        // Exceptions are reported once: a second cycle produces nothing new for this merchant.
        Flows.runJob(s.api(), "settlement-generate");
        Flows.runJob(s.api(), "reconcile");
        assertThat(s.api().get("/dashboard/reconciliation/items?status=all", Api.bearer(s.token())).body()).hasSize(4);
        assertThat(ledgerRepo.sumOfAllPostingsSigned()).isZero();
    }

    @Test
    void handCraftedFileWithDuplicateAndPhantomRows() {
        Setup s = setup();
        String p = Flows.paidPayment(s.api(), s.key(), 1_234, "automatic");
        String ref = bankRefOf(p);
        // Take the real row out of the simulator's queue so a later generate does not report it twice.
        jdbc.sql("UPDATE banksim_transactions SET settled_on = :d WHERE bank_ref = :r").param("d", LocalDate.now()).param("r", ref).update();
        String csv = "bank_ref,kind,parent_ref,amount_minor,currency,card_last4,processed_at\n"
                + ref + ",authorize,,1234,INR,4242,2026-09-12T00:00:00Z\n"
                + ref + ",authorize,,1234,INR,4242,2026-09-12T00:00:00Z\n"
                + "bref_phantom_" + System.nanoTime() + ",authorize,,999,INR,0000,2026-09-12T00:00:00Z\n";
        ingest.ingest(LocalDate.of(2000, 1, 1), "INR", csv, 3, 1234 + 1234 + 999);

        Api.Response rec = Flows.runJob(s.api(), "reconcile");
        assertThat(rec.status()).isEqualTo(200);
        var run = rec.at("/result/runs").iterator().next();
        assertThat(run.get("rows_total").asInt()).isEqualTo(3);
        assertThat(run.get("rows_matched").asInt()).isEqualTo(1);
        assertThat(run.get("items_open").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(run.get("settled_minor").asLong()).as("only the matched row is booked").isEqualTo(1_234);

        String runId = run.get("run_id").asString();
        List<String> kinds = jdbc.sql("SELECT kind FROM reconciliation_items WHERE run_id = :r ORDER BY kind").param("r", runId).query(String.class).list();
        assertThat(kinds).contains("duplicate", "missing_in_ledger");
    }

    @Test
    void retentionPrunesOperationalRowsOnly() {
        jdbc.sql("INSERT INTO job_runs (id, job_name, status, trigger, started_at, finished_at) VALUES ('job_old1', 'x', 'succeeded', 'scheduler', now() - interval '30 days', now() - interval '30 days')").update();
        Api.Response r = Flows.runJob(api(), "retention-cleanup");
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        assertThat(r.at("/result/job_runs").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM job_runs WHERE id = 'job_old1'").query(Long.class).single()).isZero();
        assertThat(r.at("/result/outbox_events").asInt()).isZero();   // nothing is 30 days old in a fresh test DB
    }
}
