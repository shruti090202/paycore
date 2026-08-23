package com.paycore.risk;

import com.paycore.risk.rules.TestCardSignalRule;
import com.paycore.support.AbstractIntegrationTest;
import com.paycore.support.Api;
import com.paycore.support.Flows;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskIT extends AbstractIntegrationTest {

    @Autowired JdbcClient jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired VelocityCounters counters;

    private record Setup(Api api, String key, String token) {
    }

    private Setup setup() {
        Api api = api();
        String token = api.signupAndGetToken();
        String key = api.post("/dashboard/api_keys", Map.of("name", "k"), Api.bearer(token)).text("/key");
        return new Setup(api, key, token);
    }

    private static Flows.Created create(Api api, String key, long amount, String email) {
        Map<String, Object> body = new HashMap<>();
        body.put("amount_minor", amount);
        body.put("currency", "INR");
        body.put("customer", Map.of("email", email));
        Api.Response r = api.post("/v1/payments", body, Api.bearer(key));
        String url = r.text("/checkout_url");
        return new Flows.Created(r.text("/id"), url.substring(url.lastIndexOf('/') + 1));
    }

    @Test
    void cleanCheckoutIsAllowedAndRecorded() {
        Setup s = setup();
        Flows.Created c = create(s.api(), s.key(), 5_000, "ok@example.test");
        assertThat(Flows.confirm(s.api(), c.checkoutToken(), Flows.CARD_OK).status()).isEqualTo(200);

        Api.Response p = s.api().get("/v1/payments/" + c.paymentId(), Api.bearer(s.key()));
        assertThat(p.at("/risk/score").asInt()).isZero();
        assertThat(p.text("/risk/decision")).isEqualTo("allow");

        Api.Response d = s.api().get("/dashboard/risk/decisions/" + c.paymentId(), Api.bearer(s.token()));
        assertThat(d.status()).isEqualTo(200);
        assertThat(d.text("/decision")).isEqualTo("allow");
        assertThat(d.at("/reasons")).isEmpty();
        assertThat(s.api().get("/dashboard/payments/" + c.paymentId(), Api.bearer(s.token())).text("/card_fingerprint")).startsWith("fp_");
    }

    @Test
    void reviewCardIsCapturedButFlaggedWithReasons() {
        Setup s = setup();
        Flows.Created c = create(s.api(), s.key(), 5_000, "review@example.test");
        Api.Response r = Flows.confirm(s.api(), c.checkoutToken(), TestCardSignalRule.REVIEW_CARD);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("/status")).isEqualTo("captured");

        Api.Response p = s.api().get("/v1/payments/" + c.paymentId(), Api.bearer(s.key()));
        assertThat(p.text("/risk/decision")).isEqualTo("review");
        assertThat(p.at("/risk/score").asInt()).isEqualTo(50);

        Api.Response detail = s.api().get("/dashboard/payments/" + c.paymentId(), Api.bearer(s.token()));
        assertThat(detail.text("/risk/decision")).isEqualTo("review");
        assertThat(detail.raw()).contains("Issuer reports elevated risk");
        assertThat(detail.at("/events")).anySatisfy(e -> assertThat(e.get("type").asString()).isEqualTo("risk.evaluated"));

        Api.Response list = s.api().get("/dashboard/risk/decisions?decision=review", Api.bearer(s.token()));
        assertThat(list.at("/data")).hasSize(1);
        assertThat(list.text("/data/0/payment_id")).isEqualTo(c.paymentId());
    }

    @Test
    void blockedCardNeverReachesTheBank() {
        Setup s = setup();
        Flows.Created c = create(s.api(), s.key(), 5_000, "block@example.test");
        Api.Response r = Flows.confirm(s.api(), c.checkoutToken(), TestCardSignalRule.BLOCK_CARD);
        assertThat(r.status()).isEqualTo(402);
        assertThat(r.text("/error/code")).isEqualTo("card_declined");
        assertThat(r.text("/error/message")).as("shopper gets a generic decline").isEqualTo("Your card was declined.");
        assertThat(r.raw()).doesNotContain("fraud").doesNotContain("risk");

        Api.Response p = s.api().get("/v1/payments/" + c.paymentId(), Api.bearer(s.key()));
        assertThat(p.text("/status")).isEqualTo("failed");
        assertThat(p.text("/failure/code")).as("merchant sees the real reason").isEqualTo("risk_blocked");
        assertThat(p.text("/risk/decision")).isEqualTo("block");

        long attempts = jdbc.sql("SELECT COUNT(*) FROM bank_attempts WHERE payment_id = :p").param("p", c.paymentId()).query(Long.class).single();
        assertThat(attempts).as("no bank call was made").isZero();
        assertThat(s.api().get("/dashboard/risk/decisions/" + c.paymentId(), Api.bearer(s.token())).at("/score").asInt()).isEqualTo(100);
    }

    @Test
    void cardVelocityAcrossPaymentsTriggersReview() {
        Setup s = setup();
        // Global rule: max 5 attempts per card per 60s. Use a card nobody else in the suite uses at scale.
        String card = Flows.CARD_MC;
        int reviewAt = -1;
        for (int i = 1; i <= 7; i++) {
            Flows.Created c = create(s.api(), s.key(), 100 + i, "velocity" + i + "@example.test");
            Flows.confirm(s.api(), c.checkoutToken(), card);
            String decision = s.api().get("/v1/payments/" + c.paymentId(), Api.bearer(s.key())).text("/risk/decision");
            if ("review".equals(decision) && reviewAt < 0) {
                reviewAt = i;
            }
        }
        assertThat(reviewAt).as("the 6th attempt within a minute is the first flagged one (or earlier if other tests used the card)")
                .isBetween(1, 6);
    }

    @Test
    void manyCardsForOneCustomerTriggersReview() {
        Setup s = setup();
        String email = "cycler-" + System.nanoTime() + "@example.test";
        List<String> cards = List.of(Flows.CARD_OK, Flows.CARD_MC, Flows.CARD_DECLINE, Flows.CARD_INSUFFICIENT);
        String last = null;
        for (String card : cards) {
            Flows.Created c = create(s.api(), s.key(), 250, email);
            Flows.confirm(s.api(), c.checkoutToken(), card);
            last = c.paymentId();
        }
        Api.Response p = s.api().get("/v1/payments/" + last, Api.bearer(s.key()));
        assertThat(p.text("/risk/decision")).as("4th distinct card for one customer within an hour").isEqualTo("review");
        assertThat(s.api().get("/dashboard/risk/decisions/" + last, Api.bearer(s.token())).raw()).contains("different cards");
    }

    @Test
    void blocklistByEmailAndByFingerprint() {
        Setup s = setup();
        // Email blocklist
        Api.Response added = s.api().post("/dashboard/risk/blocklist", Map.of("kind", "email", "value", "Banned@Example.test", "reason", "chargebacks"), Api.bearer(s.token()));
        assertThat(added.status()).isEqualTo(201);
        assertThat(added.text("/value_hash")).startsWith("eh_").doesNotContain("banned");
        Flows.Created c = create(s.api(), s.key(), 300, "banned@example.test");
        assertThat(Flows.confirm(s.api(), c.checkoutToken(), Flows.CARD_OK).status()).isEqualTo(402);
        assertThat(s.api().get("/v1/payments/" + c.paymentId(), Api.bearer(s.key())).text("/failure/code")).isEqualTo("risk_blocked");

        // Fingerprint blocklist, taken from a previous payment's detail
        Flows.Created ok = create(s.api(), s.key(), 300, "fine@example.test");
        Flows.confirm(s.api(), ok.checkoutToken(), Flows.CARD_MC);
        String fp = s.api().get("/dashboard/payments/" + ok.paymentId(), Api.bearer(s.token())).text("/card_fingerprint");
        s.api().post("/dashboard/risk/blocklist", Map.of("kind", "card_fingerprint", "value", fp), Api.bearer(s.token()));
        Flows.Created again = create(s.api(), s.key(), 300, "fine@example.test");
        assertThat(Flows.confirm(s.api(), again.checkoutToken(), Flows.CARD_MC).status()).isEqualTo(402);

        // Blocklists are per merchant: another merchant can still charge that card.
        Setup other = setup();
        Flows.Created theirs = create(other.api(), other.key(), 300, "fine@example.test");
        assertThat(Flows.confirm(other.api(), theirs.checkoutToken(), Flows.CARD_MC).status()).isEqualTo(200);

        // Unblock -> works again.
        String entryId = s.api().get("/dashboard/risk/blocklist", Api.bearer(s.token())).body().findValuesAsString("id").stream()
                .filter(id -> id.startsWith("blk_")).findFirst().orElseThrow();
        assertThat(s.api().get("/dashboard/risk/blocklist", Api.bearer(s.token())).body()).hasSize(2);
        assertThat(s.api().delete("/dashboard/risk/blocklist/" + entryId, Api.bearer(s.token())).status()).isEqualTo(204);
        assertThat(s.api().get("/dashboard/risk/blocklist", Api.bearer(s.token())).body()).hasSize(1);
        assertThat(s.api().post("/dashboard/risk/blocklist", Map.of("kind", "phone", "value", "x"), Api.bearer(s.token())).text("/error/code")).isEqualTo("kind_invalid");
    }

    @Test
    void merchantsCanOverrideAndResetRules() {
        Setup s = setup();
        Api.Response rules = s.api().get("/dashboard/risk/rules", Api.bearer(s.token()));
        assertThat(rules.body()).hasSize(5);
        assertThat(rules.body().findValuesAsString("overridden")).containsOnly("false");

        // Tighten the amount rule: block anything above 1.00
        Api.Response updated = s.api().put("/dashboard/risk/rules/amount_threshold",
                Map.of("params", Map.of("review_above_minor", 50, "block_above_minor", 100)), Api.bearer(s.token()));
        assertThat(updated.status()).isEqualTo(200);
        Flows.Created c = create(s.api(), s.key(), 150, "big@example.test");
        assertThat(Flows.confirm(s.api(), c.checkoutToken(), Flows.CARD_OK).status()).isEqualTo(402);
        assertThat(s.api().get("/dashboard/risk/decisions/" + c.paymentId(), Api.bearer(s.token())).raw()).contains("exceeds block threshold");

        // Disable it entirely
        s.api().put("/dashboard/risk/rules/amount_threshold", Map.of("enabled", false), Api.bearer(s.token()));
        Flows.Created c2 = create(s.api(), s.key(), 150, "big@example.test");
        assertThat(Flows.confirm(s.api(), c2.checkoutToken(), Flows.CARD_OK).status()).isEqualTo(200);

        // Reset to global default
        Api.Response reset = s.api().delete("/dashboard/risk/rules/amount_threshold", Api.bearer(s.token()));
        assertThat(reset.status()).isEqualTo(200);
        assertThat(reset.raw()).contains("\"overridden\":false");
        assertThat(s.api().put("/dashboard/risk/rules/nope", Map.of("enabled", false), Api.bearer(s.token())).text("/error/code")).isEqualTo("rule_type_unknown");
        assertThat(s.api().put("/dashboard/risk/rules/blocklist", Map.of("weight", 500), Api.bearer(s.token())).text("/error/code")).isEqualTo("weight_invalid");
    }

    @Test
    void velocityCountersReportUnknownWhenRedisIsDown() {
        var broken = new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
                new org.springframework.data.redis.connection.RedisStandaloneConfiguration("127.0.0.1", 1),
                org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder()
                        .commandTimeout(java.time.Duration.ofMillis(300)).build());
        broken.afterPropertiesSet();
        try {
            VelocityCounters down = new VelocityCounters(new StringRedisTemplate(broken));
            assertThat(down.incrementCardAttempts("mer_x", "fp_x", 60)).isNull();
            assertThat(down.recordCustomerCard("mer_x", "a@b.c", "fp_x", 60)).isNull();
        } finally {
            broken.destroy();
        }
        // and the live one counts
        String fp = "fp_test_" + System.nanoTime();
        assertThat(counters.incrementCardAttempts("mer_t", fp, 60)).isEqualTo(1L);
        assertThat(counters.incrementCardAttempts("mer_t", fp, 60)).isEqualTo(2L);
        assertThat(redis.getExpire("risk:card:mer_t:" + fp)).isBetween(1L, 60L);
    }
}
