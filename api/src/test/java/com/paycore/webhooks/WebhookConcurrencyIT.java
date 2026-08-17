package com.paycore.webhooks;

import com.paycore.support.AbstractIntegrationTest;
import com.paycore.support.Api;
import com.paycore.support.Flows;
import com.paycore.support.WebhookReceiver;
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

/**
 * Several dispatchers running at once (cron job + scheduler, or two Render instances) must not deliver the
 * same event twice. SKIP LOCKED partitions the claims; the lease hides claimed rows while the HTTP call runs.
 */
class WebhookConcurrencyIT extends AbstractIntegrationTest {

    @Autowired WebhookDispatcher dispatcher;
    @Autowired JdbcClient jdbc;

    @Test
    void parallelDispatchersDeliverEachEventExactlyOnce() throws Exception {
        Api api = api();
        String key = api.signupAndGetApiKey();
        String merchantId = api.get("/v1/account", Api.bearer(key)).text("/id");
        try (WebhookReceiver receiver = new WebhookReceiver()) {
            receiver.delay(20); // make the HTTP call take long enough for dispatchers to overlap
            api.post("/v1/webhook_endpoints", Map.of("url", receiver.url(), "enabled_events", List.of("payment.created")), Api.bearer(key));
            int n = 60;
            for (int i = 0; i < n; i++) {
                Flows.createPayment(api, key, 100 + i, "manual");
            }

            int dispatchers = 6;
            ExecutorService pool = Executors.newFixedThreadPool(dispatchers);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < dispatchers; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    int delivered = 0;
                    for (int round = 0; round < 10; round++) {
                        delivered += dispatcher.dispatchOnce().delivered();
                    }
                    return delivered;
                }));
            }
            go.countDown();
            int totalDelivered = 0;
            for (Future<Integer> f : results) {
                totalDelivered += f.get(120, TimeUnit.SECONDS);
            }
            pool.shutdown();

            assertThat(totalDelivered).isEqualTo(n);
            assertThat(receiver.received()).hasSize(n);
            assertThat(receiver.received()).extracting(r -> r.header("PayCore-Delivery-Id")).doesNotHaveDuplicates();
            assertThat(receiver.received()).extracting(r -> r.header("PayCore-Event-Id")).doesNotHaveDuplicates();

            long pending = jdbc.sql("SELECT COUNT(*) FROM webhook_deliveries WHERE merchant_id = :m AND status <> 'delivered'")
                    .param("m", merchantId).query(Long.class).single();
            long attempts = jdbc.sql("SELECT COUNT(*) FROM webhook_delivery_attempts a JOIN webhook_deliveries d ON d.id = a.delivery_id WHERE d.merchant_id = :m")
                    .param("m", merchantId).query(Long.class).single();
            assertThat(pending).isZero();
            assertThat(attempts).as("exactly one attempt per delivery").isEqualTo(n);
        }
    }
}
