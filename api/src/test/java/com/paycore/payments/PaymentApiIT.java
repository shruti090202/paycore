package com.paycore.payments;

import com.paycore.ledger.LedgerRepository;
import com.paycore.support.AbstractIntegrationTest;
import com.paycore.support.Api;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentApiIT extends AbstractIntegrationTest {

    @Autowired PaymentService paymentService;
    @Autowired LedgerRepository ledgerRepo;

    private static final PaymentService.CardSummary CARD = new PaymentService.CardSummary("visa", "4242", "fp_test");

    private Map<String, Object> body(long amount, String captureMethod) {
        Map<String, Object> b = new HashMap<>();
        b.put("amount_minor", amount);
        b.put("currency", "INR");
        b.put("capture_method", captureMethod);
        b.put("description", "Order #42");
        b.put("customer", Map.of("email", "Buyer@Example.com", "ref", "cust_1"));
        b.put("success_url", "https://shop.example/ok");
        b.put("cancel_url", "https://shop.example/cancel");
        b.put("metadata", Map.of("order_id", "42"));
        return b;
    }

    @Test
    void createReturnsCheckoutUrlAndTimeline() {
        Api api = api();
        String key = api.signupAndGetApiKey();

        Api.Response r = api.post("/v1/payments", body(10_000, "automatic"), Api.bearer(key));
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.text("/id")).startsWith("pay_");
        assertThat(r.text("/object")).isEqualTo("payment");
        assertThat(r.text("/status")).isEqualTo("created");
        assertThat(r.at("/amount_minor").asLong()).isEqualTo(10_000);
        assertThat(r.at("/captured_minor").asLong()).isZero();
        assertThat(r.text("/checkout_url")).startsWith("http://localhost:3000/checkout/cs_");
        assertThat(r.text("/customer/email")).isEqualTo("buyer@example.com");
        assertThat(r.text("/metadata/order_id")).isEqualTo("42");
        assertThat(r.at("/card").isMissingNode()).isTrue();
        assertThat(r.raw()).doesNotContain("checkout_token").doesNotContain("fingerprint");

        assertThat(api.get("/v1/payments/" + r.text("/id"), Api.bearer(key)).text("/status")).isEqualTo("created");
    }

    @Test
    void validationErrorsNameTheParameter() {
        Api api = api();
        String key = api.signupAndGetApiKey();

        Api.Response zero = api.post("/v1/payments", Map.of("amount_minor", 0, "currency", "INR"), Api.bearer(key));
        assertThat(zero.status()).isEqualTo(400);
        assertThat(zero.text("/error/param")).isEqualTo("amount_minor");

        Api.Response ccy = api.post("/v1/payments", Map.of("amount_minor", 100, "currency", "XXX"), Api.bearer(key));
        assertThat(ccy.status()).isEqualTo(400);
        assertThat(ccy.text("/error/code")).isEqualTo("currency_unsupported");
        assertThat(ccy.text("/error/param")).isEqualTo("currency");

        Api.Response cm = api.post("/v1/payments", Map.of("amount_minor", 100, "currency", "INR", "capture_method", "later"), Api.bearer(key));
        assertThat(cm.status()).isEqualTo(400);
        assertThat(cm.text("/error/param")).isEqualTo("capture_method");

        Api.Response url = api.post("/v1/payments", Map.of("amount_minor", 100, "currency", "INR", "success_url", "javascript:alert(1)"), Api.bearer(key));
        assertThat(url.status()).isEqualTo(400);
        assertThat(url.text("/error/param")).isEqualTo("success_url");
    }

    @Test
    void paymentsAreScopedToTheirMerchant() {
        Api api = api();
        String keyA = api.signupAndGetApiKey();
        String keyB = api.signupAndGetApiKey();
        String id = api.post("/v1/payments", body(500, "automatic"), Api.bearer(keyA)).text("/id");

        assertThat(api.get("/v1/payments/" + id, Api.bearer(keyB)).status()).isEqualTo(404);
        assertThat(api.post("/v1/payments/" + id + "/cancel", null, Api.bearer(keyB)).status()).isEqualTo(404);
        assertThat(api.get("/v1/payments/" + id + "/ledger", Api.bearer(keyB)).status()).isEqualTo(404);
        assertThat(api.get("/v1/payments", Api.bearer(keyB)).at("/data")).isEmpty();
    }

    @Test
    void manualCaptureFlowPostsToLedgerAndUpdatesBalance() {
        Api api = api();
        String key = api.signupAndGetApiKey();
        String id = api.post("/v1/payments", body(10_000, "manual"), Api.bearer(key)).text("/id");

        // Cannot capture before the bank authorized.
        Api.Response early = api.post("/v1/payments/" + id + "/capture", null, Api.bearer(key));
        assertThat(early.status()).isEqualTo(409);
        assertThat(early.text("/error/type")).isEqualTo("state_conflict");
        assertThat(early.text("/error/code")).isEqualTo("payment_state_invalid");

        // Bank says yes.
        paymentService.recordAuthorization(id, "bank_ref_1", CARD);
        Api.Response authorized = api.get("/v1/payments/" + id, Api.bearer(key));
        assertThat(authorized.text("/status")).isEqualTo("authorized");
        assertThat(authorized.text("/card/last4")).isEqualTo("4242");
        assertThat(authorized.at("/checkout_url").isMissingNode()).as("checkout closes after authorization").isTrue();

        // Partial capture of 60.00 of 100.00.
        Api.Response captured = api.post("/v1/payments/" + id + "/capture", Map.of("amount_minor", 6000), Api.bearer(key));
        assertThat(captured.status()).isEqualTo(200);
        assertThat(captured.text("/status")).isEqualTo("captured");
        assertThat(captured.at("/captured_minor").asLong()).isEqualTo(6000);

        // Ledger: DR bank_receivable 6000 / CR merchant_payable 5580 / CR fee_revenue 420  (2% + 3.00)
        Api.Response ledger = api.get("/v1/payments/" + id + "/ledger", Api.bearer(key));
        assertThat(ledger.status()).isEqualTo(200);
        assertThat(ledger.body()).hasSize(1);
        assertThat(ledger.text("/0/kind")).isEqualTo("capture");
        var postings = ledger.at("/0/postings");
        assertThat(postings).hasSize(3);
        long debits = 0, credits = 0;
        for (var p : postings) {
            if (p.get("direction").asString().equals("debit")) debits += p.get("amount_minor").asLong();
            else credits += p.get("amount_minor").asLong();
        }
        assertThat(debits).isEqualTo(6000);
        assertThat(credits).isEqualTo(6000);
        assertThat(ledger.raw()).contains("bank_receivable:INR").contains("fee_revenue:INR").contains("merchant_payable:");

        Api.Response balance = api.get("/v1/balance", Api.bearer(key));
        assertThat(balance.status()).isEqualTo(200);
        assertThat(balance.at("/available/0/balance_minor").asLong()).isEqualTo(5580);
        assertThat(balance.text("/available/0/currency")).isEqualTo("INR");

        // Capturing twice is a state conflict, and the rest of the authorization is not capturable later.
        assertThat(api.post("/v1/payments/" + id + "/capture", null, Api.bearer(key)).status()).isEqualTo(409);
        assertThat(api.post("/v1/payments/" + id + "/cancel", null, Api.bearer(key)).status()).isEqualTo(409);

        // Whole ledger still nets to zero.
        assertThat(ledgerRepo.sumOfAllPostingsSigned()).isZero();
    }

    @Test
    void captureAmountCannotExceedAuthorization() {
        Api api = api();
        String key = api.signupAndGetApiKey();
        String id = api.post("/v1/payments", body(10_000, "manual"), Api.bearer(key)).text("/id");
        paymentService.recordAuthorization(id, "bank_ref_2", CARD);

        Api.Response r = api.post("/v1/payments/" + id + "/capture", Map.of("amount_minor", 10_001), Api.bearer(key));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.text("/error/code")).isEqualTo("amount_exceeds_authorized");
        assertThat(api.get("/v1/payments/" + id, Api.bearer(key)).text("/status")).isEqualTo("authorized");
    }

    @Test
    void automaticCaptureHappensWithAuthorizationInOneTransaction() {
        Api api = api();
        String key = api.signupAndGetApiKey();
        String token = api.signupAndGetToken(); // a different merchant's dashboard token must not see this payment
        String id = api.post("/v1/payments", body(2_500, "automatic"), Api.bearer(key)).text("/id");

        paymentService.recordAuthorization(id, "bank_ref_3", CARD);

        Api.Response r = api.get("/v1/payments/" + id, Api.bearer(key));
        assertThat(r.text("/status")).isEqualTo("captured");
        assertThat(r.at("/captured_minor").asLong()).isEqualTo(2_500);

        List<String> types = paymentService.timeline(id).stream().map(PaymentEvent::type).toList();
        assertThat(types).containsExactly("payment.created", "payment.authorized", "payment.captured");

        assertThat(api.get("/dashboard/payments/" + id, Api.bearer(token)).status()).isEqualTo(404);
    }

    @Test
    void cancelBeforeCaptureAndTerminalStatesStayTerminal() {
        Api api = api();
        String key = api.signupAndGetApiKey();
        String id = api.post("/v1/payments", body(700, "manual"), Api.bearer(key)).text("/id");

        Api.Response canceled = api.post("/v1/payments/" + id + "/cancel", null, Api.bearer(key));
        assertThat(canceled.status()).isEqualTo(200);
        assertThat(canceled.text("/status")).isEqualTo("canceled");
        assertThat(api.post("/v1/payments/" + id + "/cancel", null, Api.bearer(key)).status()).isEqualTo(409);
        assertThat(api.post("/v1/payments/" + id + "/capture", null, Api.bearer(key)).status()).isEqualTo(409);
        assertThat(api.get("/v1/payments/" + id + "/ledger", Api.bearer(key)).body()).isEmpty();

        String failed = api.post("/v1/payments", body(700, "manual"), Api.bearer(key)).text("/id");
        paymentService.markFailed(failed, "card_declined", "Insufficient funds");
        Api.Response f = api.get("/v1/payments/" + failed, Api.bearer(key));
        assertThat(f.text("/status")).isEqualTo("failed");
        assertThat(f.text("/failure/code")).isEqualTo("card_declined");
    }

    @Test
    void listSupportsFiltersAndCursorPagination() {
        Api api = api();
        String key = api.signupAndGetApiKey();
        String p1 = api.post("/v1/payments", body(100, "manual"), Api.bearer(key)).text("/id");
        String p2 = api.post("/v1/payments", body(200, "manual"), Api.bearer(key)).text("/id");
        String p3 = api.post("/v1/payments", body(300, "manual"), Api.bearer(key)).text("/id");
        api.post("/v1/payments/" + p2 + "/cancel", null, Api.bearer(key));

        Api.Response page1 = api.get("/v1/payments?limit=2", Api.bearer(key));
        assertThat(page1.status()).isEqualTo(200);
        assertThat(page1.at("/has_more").asBoolean()).isTrue();
        assertThat(page1.text("/data/0/id")).isEqualTo(p3);
        assertThat(page1.text("/data/1/id")).isEqualTo(p2);

        Api.Response page2 = api.get("/v1/payments?limit=2&cursor=" + page1.text("/next_cursor"), Api.bearer(key));
        assertThat(page2.at("/has_more").asBoolean()).isFalse();
        assertThat(page2.at("/data")).hasSize(1);
        assertThat(page2.text("/data/0/id")).isEqualTo(p1);

        Api.Response canceled = api.get("/v1/payments?status=canceled", Api.bearer(key));
        assertThat(canceled.at("/data")).hasSize(1);
        assertThat(canceled.text("/data/0/id")).isEqualTo(p2);

        assertThat(api.get("/v1/payments?status=bogus", Api.bearer(key)).text("/error/code")).isEqualTo("status_invalid");
        assertThat(api.get("/v1/payments?customer_email=buyer@example.com", Api.bearer(key)).at("/data")).hasSize(3);
    }

    @Test
    void dashboardDetailShowsTimelineAndLedger() {
        Api api = api();
        String token = api.signupAndGetToken();
        String key = api.post("/dashboard/api_keys", Map.of("name", "k"), Api.bearer(token)).text("/key");
        String id = api.post("/v1/payments", body(10_000, "automatic"), Api.bearer(key)).text("/id");
        paymentService.recordAuthorization(id, "bank_ref_4", CARD);

        Api.Response d = api.get("/dashboard/payments/" + id, Api.bearer(token));
        assertThat(d.status()).isEqualTo(200);
        assertThat(d.text("/payment/status")).isEqualTo("captured");
        assertThat(d.at("/events")).hasSize(3);
        assertThat(d.text("/events/2/type")).isEqualTo("payment.captured");
        assertThat(d.at("/events/2/data/fee_minor").asLong()).isEqualTo(500);
        assertThat(d.at("/ledger")).hasSize(1);
        assertThat(d.at("/ledger/0/postings")).hasSize(3);

        Api.Response bal = api.get("/dashboard/balance", Api.bearer(token));
        assertThat(bal.at("/0/balance_minor").asLong()).isEqualTo(9_500);
    }
}
