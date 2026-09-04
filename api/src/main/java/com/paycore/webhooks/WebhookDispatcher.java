package com.paycore.webhooks;

import com.paycore.common.id.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The consumer side of the outbox. One pass = fan-out + deliver:
 * <ol>
 *   <li>Fan-out: claim events with no deliveries ({@code FOR UPDATE SKIP LOCKED}), create one pending delivery
 *       per subscribed active endpoint, mark the event fanned out. One transaction per event.</li>
 *   <li>Deliver: claim due deliveries (SKIP LOCKED + lease), COMMIT, then POST each one with no transaction
 *       open, then record the outcome. Success -> delivered; failure -> retry with jittered backoff; too many
 *       failures -> dead (manual replay from the dashboard).</li>
 * </ol>
 * Several dispatchers (cron-triggered job + in-process scheduler, or two instances) can run at once without
 * double delivery: SKIP LOCKED partitions the claims and the lease hides claimed rows until it expires.
 */
@Service
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private static final int SNIPPET = 500;

    private final WebhookRepository repo;
    private final WebhookEndpointService endpoints;
    private final WebhookProperties props;
    private final BackoffPolicy backoff;
    private final TransactionTemplate tx;
    private final RestClient http;
    private final Clock clock;

    public record Summary(int fannedOut, int deliveriesCreated, int claimed, int delivered, int retried, int dead) {
    }

    public WebhookDispatcher(WebhookRepository repo, WebhookEndpointService endpoints, WebhookProperties props,
                             PlatformTransactionManager txManager, RestClient.Builder restClientBuilder, Clock clock) {
        this.repo = repo;
        this.endpoints = endpoints;
        this.props = props;
        this.backoff = new BackoffPolicy(Duration.ofSeconds(props.baseDelaySeconds()),
                Duration.ofSeconds(props.maxDelaySeconds()), props.maxAttempts());
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                        // HTTP/1.1 only: the JDK default (HTTP/2 with h2c upgrade on plain http) is dropped by some
                        // receivers (e.g. Node servers), and webhook consumers are arbitrary third-party servers.
                        .version(java.net.http.HttpClient.Version.HTTP_1_1)
                        .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(props.httpTimeoutSeconds()));
        this.http = restClientBuilder.clone()
                .requestFactory(factory)
                .defaultStatusHandler(s -> true, (req, res) -> { /* status handled by caller */ })
                .build();
    }

    public Summary dispatchOnce() {
        Instant now = clock.instant();
        int fanned = 0, created = 0;
        // ---- fan-out ---------------------------------------------------------------------------------------
        while (true) {
            int[] counts = tx.execute(s -> {
                List<OutboxEvent> events = repo.claimUnfannedEvents(props.batchSize());
                int c = 0;
                for (OutboxEvent e : events) {
                    c += fanOut(e, now);
                }
                return new int[]{events.size(), c};
            });
            fanned += counts[0];
            created += counts[1];
            if (counts[0] < props.batchSize()) {
                break;
            }
        }
        // ---- deliver ---------------------------------------------------------------------------------------
        List<WebhookDelivery> claimed = tx.execute(s -> repo.claimDue(now, Duration.ofSeconds(props.leaseSeconds()), props.batchSize()));
        int delivered = 0, retried = 0, dead = 0;
        for (WebhookDelivery d : claimed) {
            switch (deliver(d)) {
                case DELIVERED -> delivered++;
                case RETRY -> retried++;
                case DEAD -> dead++;
            }
        }
        return new Summary(fanned, created, claimed.size(), delivered, retried, dead);
    }

    private int fanOut(OutboxEvent e, Instant now) {
        List<WebhookEndpoint> targets = new ArrayList<>();
        if ("endpoint".equals(e.aggregateType())) {
            endpoints.endpointById(e.aggregateId()).filter(WebhookEndpoint::active).ifPresent(targets::add);
        } else {
            for (WebhookEndpoint ep : repo.activeEndpoints(e.merchantId())) {
                if (ep.wants(e.type())) {
                    targets.add(ep);
                }
            }
        }
        for (WebhookEndpoint ep : targets) {
            repo.insertDelivery(new WebhookDelivery(Ids.newId(Ids.WEBHOOK_DELIVERY), e.id(), ep.id(), e.merchantId(),
                    WebhookDelivery.PENDING, 0, now, null, null, null, now, now));
        }
        repo.markFannedOut(e.id(), now);
        return targets.size();
    }

    enum Outcome { DELIVERED, RETRY, DEAD }

    /** Attempt number for this call = attempts (already incremented by the claim). */
    Outcome deliver(WebhookDelivery d) {
        int attemptNo = d.attempts() + 1; // claim incremented the DB column; the record we hold predates that
        OutboxEvent event = repo.eventById(d.eventId()).orElse(null);
        WebhookEndpoint ep = endpoints.endpointById(d.endpointId()).orElse(null);
        Instant now = clock.instant();
        if (event == null || ep == null || !ep.active()) {
            tx.executeWithoutResult(s -> repo.markDead(d.id(), null, "endpoint deleted", now));
            return Outcome.DEAD;
        }
        String body = event.payload().json();
        long ts = now.getEpochSecond();
        String signature = WebhookSignatures.header(endpoints.secretFor(ep), ts, body);

        Integer status = null;
        String error = null;
        String snippet = null;
        long started = System.nanoTime();
        try {
            ResponseEntity<String> res = http.post().uri(ep.url())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(WebhookSignatures.HEADER, signature)
                    .header("PayCore-Event-Id", event.id())
                    .header("PayCore-Delivery-Id", d.id())
                    .header("PayCore-Event-Type", event.type())
                    .header("User-Agent", "PayCore-Webhooks/1.0")
                    .body(body)
                    .retrieve().toEntity(String.class);
            status = res.getStatusCode().value();
            snippet = res.getBody() == null ? null : res.getBody().substring(0, Math.min(SNIPPET, res.getBody().length()));
        } catch (RuntimeException e) {
            error = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
            if (error.length() > SNIPPET) {
                error = error.substring(0, SNIPPET);
            }
        }
        int durationMs = (int) ((System.nanoTime() - started) / 1_000_000);
        boolean ok = status != null && status >= 200 && status < 300;
        Integer finalStatus = status;
        String finalError = ok ? null : (error != null ? error : "HTTP " + status);
        String finalSnippet = snippet;
        Instant done = clock.instant();

        Outcome outcome;
        if (ok) {
            outcome = Outcome.DELIVERED;
        } else if (backoff.exhausted(attemptNo)) {
            outcome = Outcome.DEAD;
        } else {
            outcome = Outcome.RETRY;
        }
        tx.executeWithoutResult(s -> {
            repo.recordAttempt(new WebhookDeliveryAttempt(Ids.newId("wha"), d.id(), attemptNo, finalStatus, finalError,
                    durationMs, finalSnippet, done));
            switch (outcome) {
                case DELIVERED -> repo.markDelivered(d.id(), finalStatus, done);
                case DEAD -> repo.markDead(d.id(), finalStatus, finalError, done);
                case RETRY -> repo.scheduleRetry(d.id(), finalStatus, finalError, done.plus(backoff.nextDelay(attemptNo)), done);
            }
        });
        if (outcome != Outcome.DELIVERED) {
            log.info("webhook delivery {} attempt {} -> {} ({})", d.id(), attemptNo, outcome, finalError);
        }
        return outcome;
    }

    public Map<String, Object> summaryToMap(Summary s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("events_fanned_out", s.fannedOut());
        m.put("deliveries_created", s.deliveriesCreated());
        m.put("claimed", s.claimed());
        m.put("delivered", s.delivered());
        m.put("retried", s.retried());
        m.put("dead", s.dead());
        return m;
    }
}
