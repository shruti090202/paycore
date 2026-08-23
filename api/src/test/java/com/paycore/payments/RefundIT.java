package com.paycore.payments;

import com.paycore.ledger.LedgerRepository;
import com.paycore.support.AbstractIntegrationTest;
import com.paycore.support.Api;
import com.paycore.support.Flows;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RefundIT extends AbstractIntegrationTest {

    @Autowired LedgerRepository ledgerRepo;
    @Autowired PaymentService paymentService;

    @Test
    void partialThenFullRefundWalksTheStateMachineAndTheLedger() {
        Api api = api();
        String key = api.signupAndGetApiKey();
        String id = Flows.paidPayment(api, key, 10_000, "automatic");     // fee = 200 + 300 = 500, net 9500

        Api.Response r1 = api.post("/v1/payments/" + id + "/refunds", Map.of("amount_minor", 4_000, "reason", "damaged"), Api.bearer(key));
        assertThat(r1.status()).isEqualTo(201);
        assertThat(r1.text("/id")).startsWith("rf_");
        assertThat(r1.text("/status")).isEqualTo("succeeded");
        assertThat(r1.text("/reason")).isEqualTo("damaged");

        Api.Response p1 = api.get("/v1/payments/" + id, Api.bearer(key));
        assertThat(p1.text("/status")).isEqualTo("partially_refunded");
        assertThat(p1.at("/refunded_minor").asLong()).isEqualTo(4_000);
        assertThat(api.get("/v1/balance", Api.bearer(key)).at("/available/0/balance_minor").asLong()).isEqualTo(9_500 - 4_000);

        Api.Response r2 = api.post("/v1/payments/" + id + "/refunds", null, Api.bearer(key)); // rest
        assertThat(r2.status()).isEqualTo(201);
        assertThat(r2.at("/amount_minor").asLong()).isEqualTo(6_000);
        assertThat(api.get("/v1/payments/" + id, Api.bearer(key)).text("/status")).isEqualTo("refunded");

        // Ledger: capture + 2 refunds, every entry balanced, refund legs DR merchant_payable / CR bank_receivable.
        Api.Response ledger = api.get("/v1/payments/" + id + "/ledger", Api.bearer(key));
        assertThat(ledger.body()).hasSize(1); // only the capture references the payment; refunds reference the refund
        assertThat(api.get("/v1/refunds/" + r1.text("/id"), Api.bearer(key)).status()).isEqualTo(200);
        assertThat(api.get("/v1/payments/" + id + "/refunds", Api.bearer(key)).body()).hasSize(2);
        // Merchant balance goes negative by the un-returned fee: 9500 - 10000 = -500 (a real gateway would net this off later).
        assertThat(api.get("/v1/balance", Api.bearer(key)).at("/available/0/balance_minor").asLong()).isEqualTo(-500);

        Api.Response third = api.post("/v1/payments/" + id + "/refunds", null, Api.bearer(key));
        assertThat(third.status()).isEqualTo(409);
        assertThat(third.text("/error/code")).isEqualTo("payment_fully_refunded");
        assertThat(ledgerRepo.sumOfAllPostingsSigned()).isZero();
    }

    @Test
    void cannotRefundMoreThanCapturedOrBeforeCapture() {
        Api api = api();
        String key = api.signupAndGetApiKey();
        String id = Flows.paidPayment(api, key, 10_000, "automatic");

        Api.Response over = api.post("/v1/payments/" + id + "/refunds", Map.of("amount_minor", 10_001), Api.bearer(key));
        assertThat(over.status()).isEqualTo(400);
        assertThat(over.text("/error/code")).isEqualTo("amount_exceeds_refundable");
        assertThat(over.text("/error/param")).isEqualTo("amount_minor");

        Flows.Created open = Flows.createPayment(api, key, 1_000, "manual");
        Api.Response early = api.post("/v1/payments/" + open.paymentId() + "/refunds", null, Api.bearer(key));
        assertThat(early.status()).isEqualTo(409);
        assertThat(early.text("/error/code")).isEqualTo("payment_not_captured");

        String otherKey = api.signupAndGetApiKey();
        assertThat(api.post("/v1/payments/" + id + "/refunds", null, Api.bearer(otherKey)).status()).isEqualTo(404);
    }

    @Test
    void bankDeclinedRefundIsRecordedAsFailedWithoutTouchingTheLedger() {
        Api api = api();
        String key = api.signupAndGetApiKey();
        Flows.Created c = Flows.createPayment(api, key, 10_000, "automatic");
        assertThat(Flows.confirm(api, c.checkoutToken(), Flows.CARD_REFUND_DECLINES).status()).isEqualTo(200);

        Api.Response r = api.post("/v1/payments/" + c.paymentId() + "/refunds", Map.of("amount_minor", 1_000), Api.bearer(key));
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.text("/status")).isEqualTo("failed");
        assertThat(r.text("/failure_code")).isEqualTo("refund_declined");

        Api.Response p = api.get("/v1/payments/" + c.paymentId(), Api.bearer(key));
        assertThat(p.text("/status")).isEqualTo("captured");
        assertThat(p.at("/refunded_minor").asLong()).isZero();
        assertThat(api.get("/v1/balance", Api.bearer(key)).at("/available/0/balance_minor").asLong()).isEqualTo(9_500);
        // The failed refund did not reserve anything: a full refund is still possible? No - the card always declines refunds.
        assertThat(api.post("/v1/payments/" + c.paymentId() + "/refunds", null, Api.bearer(key)).text("/status")).isEqualTo("failed");
    }

    @Test
    void bankTimeoutOnRefundReservesTheAmountUntilTheJobResolvesIt() {
        Api api = api();
        String key = api.signupAndGetApiKey();
        Flows.Created c = Flows.createPayment(api, key, 10_000, "automatic");
        assertThat(Flows.confirm(api, c.checkoutToken(), Flows.CARD_REFUND_TIMEOUT).status()).isEqualTo(200);

        Api.Response r = api.post("/v1/payments/" + c.paymentId() + "/refunds", Map.of("amount_minor", 6_000), Api.bearer(key));
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.text("/status")).isEqualTo("pending");

        // While pending, the 6000 is reserved: only 4000 can still be requested.
        Api.Response over = api.post("/v1/payments/" + c.paymentId() + "/refunds", Map.of("amount_minor", 4_001), Api.bearer(key));
        assertThat(over.status()).isEqualTo(400);
        assertThat(over.text("/error/message")).contains("refundable amount 40.00 INR");
        assertThat(api.get("/v1/payments/" + c.paymentId(), Api.bearer(key)).at("/refunded_minor").asLong()).as("not applied yet").isZero();

        Api.Response job = Flows.runJob(api, "bank-status-check");
        assertThat(job.at("/result/refunds/approved").asInt()).isGreaterThanOrEqualTo(1);

        assertThat(api.get("/v1/refunds/" + r.text("/id"), Api.bearer(key)).text("/status")).isEqualTo("succeeded");
        Api.Response p = api.get("/v1/payments/" + c.paymentId(), Api.bearer(key));
        assertThat(p.text("/status")).isEqualTo("partially_refunded");
        assertThat(p.at("/refunded_minor").asLong()).isEqualTo(6_000);

        // Running the job again must not double-apply.
        Flows.runJob(api, "bank-status-check");
        assertThat(api.get("/v1/payments/" + c.paymentId(), Api.bearer(key)).at("/refunded_minor").asLong()).isEqualTo(6_000);
        assertThat(ledgerRepo.sumOfAllPostingsSigned()).isZero();
    }

    @Test
    void dashboardDetailShowsRefundsAndBankAttempts() {
        Api api = api();
        String token = api.signupAndGetToken();
        String key = api.post("/dashboard/api_keys", Map.of("name", "k"), Api.bearer(token)).text("/key");
        String id = Flows.paidPayment(api, key, 3_000, "automatic");
        assertThat(api.post("/dashboard/payments/" + id + "/refunds", Map.of("amount_minor", 1_000), Api.bearer(token)).status()).isEqualTo(201);

        Api.Response d = api.get("/dashboard/payments/" + id, Api.bearer(token));
        assertThat(d.at("/refunds")).hasSize(1);
        assertThat(d.text("/refunds/0/status")).isEqualTo("succeeded");
        assertThat(d.at("/bank_attempts")).hasSize(2);
        assertThat(d.text("/bank_attempts/0/kind")).isEqualTo("authorize");
        assertThat(d.text("/bank_attempts/0/outcome")).isEqualTo("approved");
        assertThat(d.text("/bank_attempts/1/kind")).isEqualTo("refund");
        assertThat(d.at("/bank_attempts/0/latency_ms").asInt()).isGreaterThanOrEqualTo(0);
        assertThat(d.text("/events/5/type")).isEqualTo("refund.created");
        assertThat(d.text("/events/6/type")).isEqualTo("payment.refunded");
    }
}
