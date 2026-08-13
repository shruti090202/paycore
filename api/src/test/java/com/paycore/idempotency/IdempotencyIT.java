package com.paycore.idempotency;

import com.paycore.merchant.Merchant;
import com.paycore.merchant.MerchantService;
import com.paycore.support.AbstractIntegrationTest;
import com.paycore.support.Api;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyIT extends AbstractIntegrationTest {

    @Autowired JdbcClient jdbc;
    @Autowired IdempotencyStore store;
    @Autowired MerchantService merchants;

    private static Map<String, Object> body(long amount) {
        Map<String, Object> b = new HashMap<>();
        b.put("amount_minor", amount);
        b.put("currency", "INR");
        return b;
    }

    private static Map<String, String> headers(String key, String idemKey) {
        return Map.of("Authorization", "Bearer " + key, "Idempotency-Key", idemKey);
    }

    private long paymentsFor(String apiKeyMerchant) {
        return jdbc.sql("SELECT COUNT(*) FROM payments WHERE merchant_id = :m").param("m", apiKeyMerchant).query(Long.class).single();
    }

    @Test
    void sameKeySameBodyReplaysTheOriginalResponse() {
        Api api = api();
        String key = api.signupAndGetApiKey();
        String idem = "order-" + UUID.randomUUID();

        Api.Response first = api.post("/v1/payments", body(1_000), headers(key, idem));
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.headers().getFirst(IdempotencyFilter.REPLAYED_HEADER)).isNull();

        Api.Response second = api.post("/v1/payments", body(1_000), headers(key, idem));
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.headers().getFirst(IdempotencyFilter.REPLAYED_HEADER)).isEqualTo("true");
        assertThat(second.raw()).isEqualTo(first.raw());
        assertThat(second.text("/id")).isEqualTo(first.text("/id"));

        String merchantId = api.get("/v1/account", Api.bearer(key)).text("/id");
        assertThat(paymentsFor(merchantId)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM idempotency_keys WHERE merchant_id = :m AND idem_key = :k")
                .param("m", merchantId).param("k", idem).query(String.class).single()).isEqualTo("completed");
    }

    @Test
    void sameKeyDifferentBodyIs422() {
        Api api = api();
        String key = api.signupAndGetApiKey();
        String idem = "order-" + UUID.randomUUID();
        assertThat(api.post("/v1/payments", body(1_000), headers(key, idem)).status()).isEqualTo(201);

        Api.Response r = api.post("/v1/payments", body(2_000), headers(key, idem));
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.text("/error/type")).isEqualTo("idempotency_error");
        assertThat(r.text("/error/code")).isEqualTo("idempotency_key_reused");
        assertThat(r.text("/error/param")).isEqualTo("Idempotency-Key");

        // Same body but a different route is also a different request.
        String id = api.post("/v1/payments", body(1_000), headers(key, idem)).text("/id");
        Api.Response otherRoute = api.post("/v1/payments/" + id + "/cancel", null, headers(key, idem));
        assertThat(otherRoute.status()).isEqualTo(422);
    }

    @Test
    void keysAreScopedPerMerchantAndFailedResponsesAreReplayedToo() {
        Api api = api();
        String keyA = api.signupAndGetApiKey();
        String keyB = api.signupAndGetApiKey();
        String idem = "shared-" + UUID.randomUUID();

        String a = api.post("/v1/payments", body(1_000), headers(keyA, idem)).text("/id");
        String b = api.post("/v1/payments", body(1_000), headers(keyB, idem)).text("/id");
        assertThat(a).isNotEqualTo(b);

        // A validation error (4xx) is a real outcome of that request: it is stored and replayed.
        String bad = "bad-" + UUID.randomUUID();
        Api.Response e1 = api.post("/v1/payments", body(0), headers(keyA, bad));
        Api.Response e2 = api.post("/v1/payments", body(0), headers(keyA, bad));
        assertThat(e1.status()).isEqualTo(400);
        assertThat(e2.status()).isEqualTo(400);
        assertThat(e2.headers().getFirst(IdempotencyFilter.REPLAYED_HEADER)).isEqualTo("true");
        assertThat(e2.text("/error/request_id")).as("replayed verbatim, including the original request id")
                .isEqualTo(e1.text("/error/request_id"));
    }

    @Test
    void headerIsOptionalIgnoredOnReadsAndValidated() {
        Api api = api();
        String key = api.signupAndGetApiKey();
        String merchantId = api.get("/v1/account", Api.bearer(key)).text("/id");

        assertThat(api.post("/v1/payments", body(1_000), Api.bearer(key)).status()).isEqualTo(201);
        assertThat(api.post("/v1/payments", body(1_000), Api.bearer(key)).status()).isEqualTo(201);
        assertThat(paymentsFor(merchantId)).as("no key -> no dedup").isEqualTo(2);

        assertThat(api.get("/v1/payments", headers(key, "ignored-on-get")).status()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM idempotency_keys WHERE merchant_id = :m").param("m", merchantId)
                .query(Long.class).single()).isZero();

        Api.Response tooLong = api.post("/v1/payments", body(1_000), headers(key, "x".repeat(300)));
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.text("/error/code")).isEqualTo("idempotency_key_invalid");
    }

    @Test
    void hundredsOfParallelRequestsWithOneKeyCreateExactlyOnePayment() throws Exception {
        Api api = api();
        String key = api.signupAndGetApiKey();
        String merchantId = api.get("/v1/account", Api.bearer(key)).text("/id");
        String idem = "burst-" + UUID.randomUUID();
        int parallel = 300;

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Api.Response>> futures = new ArrayList<>();
        for (int i = 0; i < parallel; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return api.post("/v1/payments", body(4_200), headers(key, idem));
            }));
        }
        go.countDown();
        int created = 0, replayed = 0, inFlight = 0, other = 0;
        Set<String> ids = new HashSet<>();
        for (Future<Api.Response> f : futures) {
            Api.Response r = f.get(120, TimeUnit.SECONDS);
            if (r.status() == 201) {
                ids.add(r.text("/id"));
                if ("true".equals(r.headers().getFirst(IdempotencyFilter.REPLAYED_HEADER))) replayed++; else created++;
            } else if (r.status() == 409 && "idempotency_key_in_flight".equals(r.text("/error/code"))) {
                assertThat(r.headers().getFirst("Retry-After")).isNotNull();
                inFlight++;
            } else {
                other++;
            }
        }
        pool.shutdown();

        assertThat(other).isZero();
        assertThat(created).as("exactly one request did the work").isEqualTo(1);
        assertThat(ids).as("every 201 carries the same payment id").hasSize(1);
        assertThat(created + replayed + inFlight).isEqualTo(parallel);
        assertThat(paymentsFor(merchantId)).isEqualTo(1);

        // Clients that got 409 retry and get the replay.
        Api.Response retry = api.post("/v1/payments", body(4_200), headers(key, idem));
        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.headers().getFirst(IdempotencyFilter.REPLAYED_HEADER)).isEqualTo("true");
        assertThat(retry.text("/id")).isEqualTo(ids.iterator().next());
    }

    @Test
    void storeLifecycleClaimCompleteExpireRelease() {
        Merchant m = merchants.signup("Idem Co", Api.uniqueEmail(), "correct-horse-battery");
        String k = "k-" + UUID.randomUUID();
        Instant now = Instant.now();

        assertThat(store.claim(m.id(), k, "h1", "POST", "/v1/x", now)).isInstanceOf(IdempotencyStore.Claimed.class);
        IdempotencyStore.Claim again = store.claim(m.id(), k, "h1", "POST", "/v1/x", now);
        assertThat(again).isInstanceOf(IdempotencyStore.Existing.class);
        assertThat(((IdempotencyStore.Existing) again).record().completed()).isFalse();

        store.complete(m.id(), k, 201, "application/json", "{\"ok\":true}", now);
        IdempotencyStore.Claim done = store.claim(m.id(), k, "h1", "POST", "/v1/x", now);
        assertThat(((IdempotencyStore.Existing) done).record().completed()).isTrue();
        assertThat(((IdempotencyStore.Existing) done).record().responseBody()).isEqualTo("{\"ok\":true}");

        // Past its TTL the key is forgotten and can be claimed afresh.
        Instant later = now.plus(IdempotencyStore.TTL).plusSeconds(1);
        assertThat(store.claim(m.id(), k, "h2", "POST", "/v1/x", later)).isInstanceOf(IdempotencyStore.Claimed.class);

        // A released (deleted) claim can be re-claimed immediately.
        store.delete(m.id(), k);
        assertThat(store.claim(m.id(), k, "h3", "POST", "/v1/x", later)).isInstanceOf(IdempotencyStore.Claimed.class);
        assertThat(store.deleteExpired(later.plus(IdempotencyStore.TTL).plusSeconds(1), 100)).isGreaterThanOrEqualTo(1);
    }
}
