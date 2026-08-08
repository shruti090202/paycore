package com.paycore.support;

import java.util.HashMap;
import java.util.Map;

/** Multi-step API flows shared by integration tests. */
public final class Flows {

    public static final String CARD_OK = "4242424242424242";
    public static final String CARD_MC = "5555555555554444";
    public static final String CARD_DECLINE = "4000000000000002";
    public static final String CARD_INSUFFICIENT = "4000000000009995";
    public static final String CARD_TIMEOUT_APPROVED = "4000000000005126";
    public static final String CARD_TIMEOUT_DECLINED = "4000000000000341";
    public static final String CARD_TIMEOUT_LOST = "4000000000000259";
    public static final String CARD_REFUND_DECLINES = "4000000000003063";
    public static final String CARD_REFUND_TIMEOUT = "4000000000003220";

    private Flows() {
    }

    public record Created(String paymentId, String checkoutToken) {
    }

    public static Created createPayment(Api api, String key, long amountMinor, String captureMethod) {
        Map<String, Object> body = new HashMap<>();
        body.put("amount_minor", amountMinor);
        body.put("currency", "INR");
        body.put("capture_method", captureMethod);
        body.put("success_url", "https://shop.example/ok");
        body.put("cancel_url", "https://shop.example/cancel");
        body.put("customer", Map.of("email", "buyer@example.test"));
        Api.Response r = api.post("/v1/payments", body, Api.bearer(key));
        if (r.status() != 201) {
            throw new IllegalStateException("create failed: " + r.raw());
        }
        String url = r.text("/checkout_url");
        return new Created(r.text("/id"), url.substring(url.lastIndexOf('/') + 1));
    }

    public static Map<String, Object> card(String number) {
        return Map.of("card_number", number, "exp_month", 12, "exp_year", 2030, "cvc", "123", "cardholder_name", "Test Buyer");
    }

    public static Api.Response confirm(Api api, String token, String cardNumber) {
        return api.post("/checkout/sessions/" + token + "/confirm", card(cardNumber), Api.none());
    }

    /** Create + confirm with the approved card; returns the payment id (captured for automatic, authorized for manual). */
    public static String paidPayment(Api api, String key, long amountMinor, String captureMethod) {
        Created c = createPayment(api, key, amountMinor, captureMethod);
        Api.Response r = confirm(api, c.checkoutToken(), CARD_OK);
        if (r.status() != 200) {
            throw new IllegalStateException("confirm failed: " + r.raw());
        }
        return c.paymentId();
    }

    public static Api.Response runJob(Api api, String name) {
        return api.post("/internal/jobs/" + name, null, Map.of("X-Internal-Token", "test-internal-token"));
    }
}
