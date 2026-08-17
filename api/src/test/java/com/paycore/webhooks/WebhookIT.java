package com.paycore.webhooks;

import com.paycore.support.AbstractIntegrationTest;
import com.paycore.support.Api;
import com.paycore.support.Flows;
import com.paycore.support.WebhookReceiver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookIT extends AbstractIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired JdbcClient jdbc;

    private record Setup(Api api, String key, String token, String endpointId, String secret, WebhookReceiver receiver) {
    }

    private Setup setup(List<String> events) throws Exception {
        Api api = api();
        String token = api.signupAndGetToken();
        String key = api.post("/dashboard/api_keys", Map.of("name", "k"), Api.bearer(token)).text("/key");
        WebhookReceiver receiver = new WebhookReceiver();
        Map<String, Object> body = events == null
                ? Map.of("url", receiver.url(), "description", "test")
                : Map.of("url", receiver.url(), "enabled_events", events, "description", "test");
        Api.Response ep = api.post("/v1/webhook_endpoints", body, Api.bearer(key));
        assertThat(ep.status()).as(ep.raw()).isEqualTo(201);
        return new Setup(api, key, token, ep.text("/id"), ep.text("/secret"), receiver);
    }

    @Test
    void endpointSecretIsShownOnceEncryptedAtRestAndRevealableFromTheDashboard() throws Exception {
        Setup s = setup(null);
        try (s.receiver) {
            assertThat(s.secret()).startsWith("whsec_").hasSize(6 + 32);
            Api.Response list = s.api().get("/v1/webhook_endpoints", Api.bearer(s.key()));
            assertThat(list.raw()).doesNotContain(s.secret());
            assertThat(list.at("/0/enabled_events")).isEmpty();

            byte[] stored = jdbc.sql("SELECT secret_enc FROM webhook_endpoints WHERE id = :id").param("id", s.endpointId())
                    .query(byte[].class).single();
            assertThat(new String(stored)).doesNotContain("whsec");
            assertThat(s.api().get("/dashboard/webhook_endpoints/" + s.endpointId() + "/secret", Api.bearer(s.token())).text("/secret"))
                    .isEqualTo(s.secret());

            // Production-style URL rules: bad scheme / unknown event type
            assertThat(s.api().post("/v1/webhook_endpoints", Map.of("url", "ftp://x/y"), Api.bearer(s.key())).text("/error/code")).isEqualTo("url_scheme");
            assertThat(s.api().post("/v1/webhook_endpoints", Map.of("url", s.receiver().url(), "enabled_events", List.of("nope")),
                    Api.bearer(s.key())).text("/error/code")).isEqualTo("event_type_unknown");
        }
    }

    @Test
    void paymentLifecycleIsDeliveredSignedAndInOrder() throws Exception {
        Setup s = setup(null);
        try (s.receiver) {
            String paymentId = Flows.paidPayment(s.api(), s.key(), 10_000, "automatic");
            // Nothing is sent until a dispatcher runs; the events already exist (outbox written in the tx).
            assertThat(s.receiver().received()).isEmpty();
            Api.Response events = s.api().get("/v1/events", Api.bearer(s.key()));
            assertThat(events.at("/data")).extracting(n -> n.get("type").asString())
                    .containsExactly("payment.captured", "payment.authorized", "payment.created");

            Api.Response job = Flows.runJob(s.api(), "webhook-dispatch");
            assertThat(job.status()).as(job.raw()).isEqualTo(200);
            assertThat(job.at("/result/delivered").asInt()).isEqualTo(3);

            List<WebhookReceiver.Received> got = s.receiver().received();
            assertThat(got).hasSize(3);
            assertThat(got).extracting(r -> r.header("PayCore-Event-Type"))
                    .containsExactly("payment.created", "payment.authorized", "payment.captured");
            for (WebhookReceiver.Received r : got) {
                assertThat(r.header("Content-Type")).startsWith("application/json");
                assertThat(r.header("User-Agent")).startsWith("PayCore-Webhooks");
                assertThat(r.header("PayCore-Event-Id")).startsWith("evt_");
                assertThat(r.header("PayCore-Delivery-Id")).startsWith("whd_");
                assertThat(WebhookSignatures.verify(s.secret(), r.header(WebhookSignatures.HEADER), r.body(),
                        System.currentTimeMillis() / 1000, 300)).as("signature verifies with the endpoint secret").isTrue();
                assertThat(WebhookSignatures.verify("whsec_wrong", r.header(WebhookSignatures.HEADER), r.body(),
                        System.currentTimeMillis() / 1000, 300)).isFalse();
                JsonNode body = JSON.readTree(r.body());
                assertThat(body.get("object").asString()).isEqualTo("event");
                assertThat(body.get("livemode").asBoolean()).isFalse();
                assertThat(body.at("/data/object/id").asString()).isEqualTo(paymentId);
                assertThat(body.at("/data/object/amount_minor").asLong()).as("snake_case like the API").isEqualTo(10_000);
            }
            // The captured event carries the captured state; the created event carries the created state (snapshots).
            assertThat(JSON.readTree(got.get(0).body()).at("/data/object/status").asString()).isEqualTo("created");
            assertThat(JSON.readTree(got.get(2).body()).at("/data/object/status").asString()).isEqualTo("captured");
            assertThat(got.get(2).body()).doesNotContain("4242424242424242").doesNotContain("checkout_token");

            Api.Response deliveries = s.api().get("/dashboard/webhook_deliveries", Api.bearer(s.token()));
            assertThat(deliveries.at("/data")).hasSize(3);
            assertThat(deliveries.at("/data")).allSatisfy(d -> assertThat(d.get("status").asString()).isEqualTo("delivered"));
            Api.Response one = s.api().get("/dashboard/webhook_deliveries/" + deliveries.text("/data/0/id"), Api.bearer(s.token()));
            assertThat(one.at("/attempt_log")).hasSize(1);
            assertThat(one.at("/attempt_log/0/status_code").asInt()).isEqualTo(200);

            // Idempotent: a second dispatch sends nothing.
            assertThat(Flows.runJob(s.api(), "webhook-dispatch").at("/result/claimed").asInt()).isZero();
            assertThat(s.receiver().received()).hasSize(3);
        }
    }

    @Test
    void endpointFilterAndRefundEvents() throws Exception {
        Setup s = setup(List.of("refund.succeeded", "payment.refunded"));
        try (s.receiver) {
            String paymentId = Flows.paidPayment(s.api(), s.key(), 5_000, "automatic");
            s.api().post("/v1/payments/" + paymentId + "/refunds", Map.of("amount_minor", 1_000), Api.bearer(s.key()));
            Flows.runJob(s.api(), "webhook-dispatch");
            assertThat(s.receiver().received()).extracting(r -> r.header("PayCore-Event-Type"))
                    .containsExactlyInAnyOrder("payment.refunded", "refund.succeeded");
            JsonNode refund = JSON.readTree(s.receiver().received().stream()
                    .filter(r -> r.header("PayCore-Event-Type").equals("refund.succeeded")).findFirst().orElseThrow().body());
            assertThat(refund.at("/data/object/object").asString()).isEqualTo("refund");
            assertThat(refund.at("/data/object/amount_minor").asLong()).isEqualTo(1_000);
        }
    }

    @Test
    void failuresRetryWithBackoffThenDeadLetterThenManualReplay() throws Exception {
        Setup s = setup(List.of("ping"));
        try (s.receiver) {
            s.receiver().failNext(3, 503);                        // exactly max_attempts (3 in tests) failures
            String eventId = s.api().post("/v1/webhook_endpoints/" + s.endpointId() + "/test", null, Api.bearer(s.key())).text("/event_id");
            assertThat(eventId).startsWith("evt_");

            Api.Response r1 = Flows.runJob(s.api(), "webhook-dispatch");
            assertThat(r1.at("/result/dead").asInt()).as(r1.raw()).isEqualTo(1);   // base delay 0 -> all 3 attempts in one job run
            assertThat(s.receiver().received()).hasSize(3);

            Api.Response list = s.api().get("/dashboard/webhook_deliveries?status=dead", Api.bearer(s.token()));
            assertThat(list.at("/data")).hasSize(1);
            String deliveryId = list.text("/data/0/id");
            Api.Response detail = s.api().get("/dashboard/webhook_deliveries/" + deliveryId, Api.bearer(s.token()));
            assertThat(detail.text("/status")).isEqualTo("dead");
            assertThat(detail.at("/attempts").asInt()).isEqualTo(3);
            assertThat(detail.at("/attempt_log")).hasSize(3);
            assertThat(detail.at("/attempt_log/0/status_code").asInt()).isEqualTo(503);
            assertThat(detail.text("/last_error")).isEqualTo("HTTP 503");
            assertThat(s.api().get("/dashboard/webhook_deliveries/summary", Api.bearer(s.token())).at("/dead").asInt()).isEqualTo(1);

            // Endpoint fixed; operator replays from the dashboard.
            Api.Response replay = s.api().post("/dashboard/webhook_deliveries/" + deliveryId + "/replay", null, Api.bearer(s.token()));
            assertThat(replay.status()).isEqualTo(200);
            assertThat(replay.text("/status")).isEqualTo("pending");
            Flows.runJob(s.api(), "webhook-dispatch");
            Api.Response after = s.api().get("/dashboard/webhook_deliveries/" + deliveryId, Api.bearer(s.token()));
            assertThat(after.text("/status")).isEqualTo("delivered");
            assertThat(after.at("/attempts").asInt()).isEqualTo(4);
            assertThat(after.at("/attempt_log")).hasSize(4);
            assertThat(s.receiver().received()).hasSize(4);
            assertThat(s.receiver().received().get(3).header("PayCore-Event-Id")).isEqualTo(eventId);
        }
    }

    @Test
    void deletedEndpointsGetNothingAndOtherMerchantsCannotSeeDeliveries() throws Exception {
        Setup s = setup(null);
        try (s.receiver) {
            assertThat(s.api().delete("/v1/webhook_endpoints/" + s.endpointId(), Api.bearer(s.key())).status()).isEqualTo(204);
            Flows.paidPayment(s.api(), s.key(), 1_000, "automatic");
            Flows.runJob(s.api(), "webhook-dispatch");
            assertThat(s.receiver().received()).isEmpty();
            assertThat(s.api().get("/dashboard/webhook_deliveries", Api.bearer(s.token())).at("/data")).isEmpty();
            assertThat(s.api().post("/v1/webhook_endpoints/" + s.endpointId() + "/test", null, Api.bearer(s.key())).status()).isEqualTo(409);

            String otherToken = s.api().signupAndGetToken();
            assertThat(s.api().get("/dashboard/webhook_endpoints/" + s.endpointId() + "/secret", Api.bearer(otherToken)).status()).isEqualTo(404);
        }
    }

    @Test
    void outboxRowIsWrittenInThePaymentTransactionAndCannotBeDeleted() throws Exception {
        Api api = api();
        String key = api.signupAndGetApiKey();
        String merchantId = api.get("/v1/account", Api.bearer(key)).text("/id");
        Flows.createPayment(api, key, 100, "automatic");
        long events = jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE merchant_id = :m").param("m", merchantId).query(Long.class).single();
        assertThat(events).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.sql("DELETE FROM outbox_events WHERE merchant_id = :m").param("m", merchantId).update())
                .hasMessageContaining("append-only");
    }
}
