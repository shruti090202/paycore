package com.paycore.payments;

import com.paycore.ledger.LedgerRepository;
import com.paycore.support.AbstractIntegrationTest;
import com.paycore.support.Api;
import com.paycore.support.Flows;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutIT extends AbstractIntegrationTest {

    @Autowired PaymentService paymentService;
    @Autowired LedgerRepository ledgerRepo;
    @Autowired JdbcClient jdbc;

    @Test
    void sessionExposesWhatTheCheckoutPageNeedsAndNothingSecret() {
        Api api = api();
        String key = api.signupAndGetApiKey();
        Flows.Created c = Flows.createPayment(api, key, 10_000, "automatic");

        Api.Response s = api.get("/checkout/sessions/" + c.checkoutToken(), Api.none());
        assertThat(s.status()).isEqualTo(200);
        assertThat(s.text("/payment_id")).isEqualTo(c.paymentId());
        assertThat(s.text("/status")).isEqualTo("created");
        assertThat(s.at("/amount_minor").asLong()).isEqualTo(10_000);
        assertThat(s.text("/merchant_name")).isEqualTo("Test Merchant");
        assertThat(s.at("/test_cards")).isNotEmpty();
        assertThat(s.raw()).doesNotContain("sk_test").doesNotContain("merchant_id");

        assertThat(api.get("/checkout/sessions/cs_doesnotexist", Api.none()).status()).isEqualTo(404);
    }

    @Test
    void approvedCardCapturesAutomaticallyAndRedirects() {
        Api api = api();
        String key = api.signupAndGetApiKey();
        Flows.Created c = Flows.createPayment(api, key, 10_000, "automatic");

        Api.Response r = Flows.confirm(api, c.checkoutToken(), Flows.CARD_OK);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("/result")).isEqualTo("approved");
        assertThat(r.text("/status")).isEqualTo("captured");
        assertThat(r.text("/redirect_url")).isEqualTo("https://shop.example/ok?payment_id=" + c.paymentId());

        Api.Response p = api.get("/v1/payments/" + c.paymentId(), Api.bearer(key));
        assertThat(p.text("/status")).isEqualTo("captured");
        assertThat(p.text("/card/brand")).isEqualTo("visa");
        assertThat(p.text("/card/last4")).isEqualTo("4242");
        assertThat(p.text("/bank_ref")).startsWith("bref_");
        assertThat(p.raw()).doesNotContain("4242424242424242");

        List<String> types = paymentService.timeline(c.paymentId()).stream().map(PaymentEvent::type).toList();
        assertThat(types).containsExactly("payment.created", "risk.evaluated", "payment.pending_bank", "payment.authorized", "payment.captured");

        // The bank's own books have the authorization under our reference.
        assertThat(jdbc.sql("SELECT outcome FROM banksim_transactions WHERE bank_ref = :r").param("r", p.text("/bank_ref"))
                .query(String.class).single()).isEqualTo("approved");
        // And the checkout session is closed.
        assertThat(Flows.confirm(api, c.checkoutToken(), Flows.CARD_OK).text("/error/code")).isEqualTo("checkout_session_closed");
        assertThat(api.get("/checkout/sessions/" + c.checkoutToken(), Api.none()).text("/redirect_url")).contains("payment_id=");
    }

    @Test
    void cardNumberWithSpacesGoesThroughTheWholeFlow() {
        Api api = api();
        String key = api.signupAndGetApiKey();
        Flows.Created c = Flows.createPayment(api, key, 1_000, "automatic");
        Api.Response r = api.post("/checkout/sessions/" + c.checkoutToken() + "/confirm",
                Map.of("card_number", "4242 4242 4242 4242", "exp_month", 12, "exp_year", 30, "cvc", "123"), Api.none());
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("/status")).isEqualTo("captured");
    }

    @Test
    void manualCaptureStopsAtAuthorized() {
        Api api = api();
        String key = api.signupAndGetApiKey();
        Flows.Created c = Flows.createPayment(api, key, 5_000, "manual");
        Api.Response r = Flows.confirm(api, c.checkoutToken(), Flows.CARD_MC);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("/status")).isEqualTo("authorized");
        assertThat(api.get("/v1/payments/" + c.paymentId() + "/ledger", Api.bearer(key)).body()).as("no ledger entry for an auth").isEmpty();
        assertThat(api.post("/v1/payments/" + c.paymentId() + "/capture", null, Api.bearer(key)).text("/status")).isEqualTo("captured");
    }

    @Test
    void declinedCardFailsThePaymentWith402() {
        Api api = api();
        String key = api.signupAndGetApiKey();
        Flows.Created c = Flows.createPayment(api, key, 5_000, "automatic");

        Api.Response r = Flows.confirm(api, c.checkoutToken(), Flows.CARD_INSUFFICIENT);
        assertThat(r.status()).isEqualTo(402);
        assertThat(r.text("/error/type")).isEqualTo("card_error");
        assertThat(r.text("/error/code")).isEqualTo("insufficient_funds");
        assertThat(r.text("/error/message")).isEqualTo("Your card has insufficient funds.");

        Api.Response p = api.get("/v1/payments/" + c.paymentId(), Api.bearer(key));
        assertThat(p.text("/status")).isEqualTo("failed");
        assertThat(p.text("/failure/code")).isEqualTo("insufficient_funds");
        assertThat(api.get("/v1/payments/" + c.paymentId() + "/ledger", Api.bearer(key)).body()).isEmpty();
    }

    @Test
    void nonTestCardsAreRefusedBeforeAnythingIsRecorded() {
        Api api = api();
        String key = api.signupAndGetApiKey();
        Flows.Created c = Flows.createPayment(api, key, 5_000, "automatic");

        Api.Response r = Flows.confirm(api, c.checkoutToken(), "4111111111111111");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.text("/error/code")).isEqualTo("card_not_test_card");
        assertThat(r.raw()).doesNotContain("4111111111111111");
        assertThat(api.get("/v1/payments/" + c.paymentId(), Api.bearer(key)).text("/status")).as("still open").isEqualTo("created");

        Api.Response bad = api.post("/checkout/sessions/" + c.checkoutToken() + "/confirm",
                Map.of("card_number", "4242424242424242", "exp_month", 1, "exp_year", 2020, "cvc", "123"), Api.none());
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.text("/error/code")).isEqualTo("card_expired");
    }

    @Test
    void bankTimeoutLeavesPaymentPendingAndTheJobResolvesItApproved() {
        Api api = api();
        String key = api.signupAndGetApiKey();
        Flows.Created c = Flows.createPayment(api, key, 7_500, "automatic");

        Api.Response r = Flows.confirm(api, c.checkoutToken(), Flows.CARD_TIMEOUT_APPROVED);
        assertThat(r.status()).isEqualTo(202);
        assertThat(r.text("/result")).isEqualTo("pending");
        assertThat(r.text("/status")).isEqualTo("pending_bank");

        Api.Response before = api.get("/v1/payments/" + c.paymentId(), Api.bearer(key));
        assertThat(before.text("/status")).isEqualTo("pending_bank");
        assertThat(before.text("/card/last4")).as("card summary is kept for the resolution").isEqualTo("5126");
        // Nothing can be captured or canceled while unknown.
        assertThat(api.post("/v1/payments/" + c.paymentId() + "/capture", null, Api.bearer(key)).status()).isEqualTo(409);
        assertThat(api.post("/v1/payments/" + c.paymentId() + "/cancel", null, Api.bearer(key)).status()).isEqualTo(409);
        assertThat(Flows.confirm(api, c.checkoutToken(), Flows.CARD_OK).text("/error/code")).isEqualTo("checkout_in_progress");

        Api.Response job = Flows.runJob(api, "bank-status-check");
        assertThat(job.status()).isEqualTo(200);
        assertThat(job.text("/status")).isEqualTo("succeeded");
        assertThat(job.at("/result/payments/approved").asInt()).isGreaterThanOrEqualTo(1);

        Api.Response after = api.get("/v1/payments/" + c.paymentId(), Api.bearer(key));
        assertThat(after.text("/status")).isEqualTo("captured");
        assertThat(after.at("/captured_minor").asLong()).isEqualTo(7_500);
        assertThat(api.get("/v1/payments/" + c.paymentId() + "/ledger", Api.bearer(key)).body()).hasSize(1);

        String token = api.signupAndGetToken();
        // Bank attempt shows timeout + resolution in the dashboard of the owning merchant (not this one).
        assertThat(api.get("/dashboard/payments/" + c.paymentId(), Api.bearer(token)).status()).isEqualTo(404);
        assertThat(jdbc.sql("SELECT outcome || '/' || resolution FROM bank_attempts WHERE payment_id = :p").param("p", c.paymentId())
                .query(String.class).single()).isEqualTo("timeout/approved");
    }

    @Test
    void bankTimeoutResolvedAsDeclinedFailsThePayment() {
        Api api = api();
        String key = api.signupAndGetApiKey();
        Flows.Created c = Flows.createPayment(api, key, 7_500, "automatic");
        assertThat(Flows.confirm(api, c.checkoutToken(), Flows.CARD_TIMEOUT_DECLINED).status()).isEqualTo(202);

        Flows.runJob(api, "bank-status-check");

        Api.Response after = api.get("/v1/payments/" + c.paymentId(), Api.bearer(key));
        assertThat(after.text("/status")).isEqualTo("failed");
        assertThat(after.text("/failure/code")).isEqualTo("generic_decline");
    }

    @Test
    void requestTheBankNeverReceivedIsFailedAfterTheGracePeriod() {
        Api api = api();
        String key = api.signupAndGetApiKey();
        Flows.Created c = Flows.createPayment(api, key, 7_500, "automatic");
        assertThat(Flows.confirm(api, c.checkoutToken(), Flows.CARD_TIMEOUT_LOST).status()).isEqualTo(202);

        Flows.runJob(api, "bank-status-check"); // grace period is 0 in tests

        Api.Response after = api.get("/v1/payments/" + c.paymentId(), Api.bearer(key));
        assertThat(after.text("/status")).isEqualTo("failed");
        assertThat(after.text("/failure/code")).isEqualTo("bank_unreachable");
        assertThat(jdbc.sql("SELECT resolution FROM bank_attempts WHERE payment_id = :p").param("p", c.paymentId())
                .query(String.class).single()).isEqualTo("not_found");
    }

    @Test
    void shopperCanCancelAnOpenSession() {
        Api api = api();
        String key = api.signupAndGetApiKey();
        Flows.Created c = Flows.createPayment(api, key, 7_500, "automatic");

        Api.Response r = api.post("/checkout/sessions/" + c.checkoutToken() + "/cancel", null, Api.none());
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("/status")).isEqualTo("canceled");
        assertThat(r.text("/redirect_url")).isEqualTo("https://shop.example/cancel");
        assertThat(Flows.confirm(api, c.checkoutToken(), Flows.CARD_OK).status()).isEqualTo(409);
    }

    @Test
    void wholeLedgerStillNetsToZero() {
        assertThat(ledgerRepo.sumOfAllPostingsSigned()).isZero();
    }
}
