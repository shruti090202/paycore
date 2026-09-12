package com.paycore.payments;

import com.paycore.ledger.LedgerRepository;
import com.paycore.support.AbstractIntegrationTest;
import com.paycore.support.Api;
import com.paycore.support.Flows;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Hundreds of concurrent refund requests against ONE payment. */
class RefundConcurrencyIT extends AbstractIntegrationTest {

    private static final int PARALLEL = 200;
    private static final long CAPTURED = 10_000;
    private static final long EACH = 100;          // 100 refunds of 100 would exactly exhaust; 200 are attempted

    @Autowired LedgerRepository ledgerRepo;
    @Autowired JdbcClient jdbc;

    @Test
    void parallelRefundsNeverExceedTheCapturedAmount() throws Exception {
        Api api = api();
        String key = api.signupAndGetApiKey();
        String id = Flows.paidPayment(api, key, CAPTURED, "automatic");

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < PARALLEL; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return api.post("/v1/payments/" + id + "/refunds", Map.of("amount_minor", EACH), Api.bearer(key)).status();
            }));
        }
        go.countDown();
        int created = 0, rejected = 0, other = 0;
        for (Future<Integer> f : results) {
            int s = f.get(120, TimeUnit.SECONDS);
            if (s == 201) created++;
            else if (s == 409 || s == 400) rejected++;
            else other++;
        }
        pool.shutdown();

        assertThat(other).as("no 5xx or unexpected statuses").isZero();
        assertThat(created).isEqualTo(CAPTURED / EACH);     // exactly 100 refunds accepted
        assertThat(rejected).isEqualTo(PARALLEL - CAPTURED / EACH);

        // database is the oracle
        long succeededSum = jdbc.sql("SELECT COALESCE(SUM(amount_minor),0) FROM refunds WHERE payment_id = :p AND status = 'succeeded'")
                .param("p", id).query(Long.class).single();
        long pendingCount = jdbc.sql("SELECT COUNT(*) FROM refunds WHERE payment_id = :p AND status = 'pending'")
                .param("p", id).query(Long.class).single();
        Map<String, Object> pay = jdbc.sql("SELECT status, captured_minor, refunded_minor FROM payments WHERE id = :p")
                .param("p", id).query().singleRow();

        assertThat(succeededSum).isEqualTo(CAPTURED);
        assertThat(pendingCount).isZero();
        assertThat(pay.get("status")).isEqualTo("refunded");
        assertThat(((Number) pay.get("refunded_minor")).longValue()).isEqualTo(CAPTURED);
        assertThat(((Number) pay.get("captured_minor")).longValue()).isEqualTo(CAPTURED);

        long refundEntries = jdbc.sql("SELECT COUNT(*) FROM journal_entries WHERE kind = 'refund' AND reference_id IN "
                        + "(SELECT id FROM refunds WHERE payment_id = :p)").param("p", id).query(Long.class).single();
        assertThat(refundEntries).isEqualTo(CAPTURED / EACH);
        assertThat(ledgerRepo.sumOfAllPostingsSigned()).isZero();
        // Merchant owes back everything captured minus the fee it never got back: 9500 - 10000.
        assertThat(api.get("/v1/balance", Api.bearer(key)).at("/available/0/balance_minor").asLong()).isEqualTo(9_500 - CAPTURED);
    }

    @Test
    void parallelCapturesOnOneAuthorizationCaptureExactlyOnce() throws Exception {
        Api api = api();
        String key = api.signupAndGetApiKey();
        String id = Flows.paidPayment(api, key, 5_000, "manual");   // authorized, not captured

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return api.post("/v1/payments/" + id + "/capture", null, Api.bearer(key)).status();
            }));
        }
        go.countDown();
        int ok = 0;
        for (Future<Integer> f : results) {
            if (f.get(60, TimeUnit.SECONDS) == 200) ok++;
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
        long captureEntries = jdbc.sql("SELECT COUNT(*) FROM journal_entries WHERE kind = 'capture' AND reference_id = :p")
                .param("p", id).query(Long.class).single();
        assertThat(captureEntries).isEqualTo(1);
    }
}
