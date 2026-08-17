package com.paycore.webhooks;

import com.paycore.common.crypto.AesGcm;
import com.paycore.common.error.PayCoreException;
import com.paycore.common.id.Ids;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class WebhookEndpointService {

    public static final List<String> EVENT_TYPES = List.of(
            "payment.created", "payment.authorized", "payment.captured", "payment.failed", "payment.canceled",
            "payment.refunded", "refund.created", "refund.succeeded", "refund.failed", "ping");

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WebhookRepository repo;
    private final OutboxWriter outbox;
    private final AesGcm crypto;
    private final WebhookUrlPolicy urlPolicy;
    private final Clock clock;

    public WebhookEndpointService(WebhookRepository repo, OutboxWriter outbox, AesGcm crypto, WebhookUrlPolicy urlPolicy,
                                  Clock clock) {
        this.repo = repo;
        this.outbox = outbox;
        this.crypto = crypto;
        this.urlPolicy = urlPolicy;
        this.clock = clock;
    }

    public record Created(WebhookEndpoint endpoint, String secret) {
    }

    @Transactional
    public Created create(String merchantId, String url, List<String> enabledEvents, String description) {
        urlPolicy.validate(url);
        List<String> events = enabledEvents == null ? List.of() : enabledEvents;
        for (String e : events) {
            if (!EVENT_TYPES.contains(e)) {
                throw PayCoreException.invalid("event_type_unknown", "Unknown event type '" + e + "'; known: " + EVENT_TYPES, "enabled_events");
            }
        }
        String secret = newSecret();
        Instant now = clock.instant();
        WebhookEndpoint ep = new WebhookEndpoint(Ids.newId(Ids.WEBHOOK_ENDPOINT), merchantId, url, crypto.encrypt(secret),
                events, description, true, now, now);
        repo.insertEndpoint(ep);
        return new Created(ep, secret);
    }

    public List<WebhookEndpoint> list(String merchantId) {
        return repo.endpointsForMerchant(merchantId);
    }

    public WebhookEndpoint require(String merchantId, String id) {
        return repo.endpoint(id, merchantId).orElseThrow(() -> PayCoreException.notFound("webhook_endpoint", id));
    }

    /** The signing secret is reversible on purpose (encrypted, not hashed) so merchants can re-read it. */
    public String revealSecret(String merchantId, String id) {
        return crypto.decrypt(require(merchantId, id).secretEnc());
    }

    String secretFor(WebhookEndpoint ep) {
        return crypto.decrypt(ep.secretEnc());
    }

    @Transactional
    public void delete(String merchantId, String id) {
        if (repo.deactivateEndpoint(id, merchantId, clock.instant()) == 0) {
            throw PayCoreException.notFound("webhook_endpoint", id);
        }
    }

    /** Emits a {@code ping} event addressed to one endpoint (fan-out honours the target). */
    @Transactional
    public OutboxEvent sendTest(String merchantId, String endpointId) {
        WebhookEndpoint ep = require(merchantId, endpointId);
        if (!ep.active()) {
            throw PayCoreException.stateConflict("endpoint_inactive", "This endpoint has been deleted");
        }
        return outbox.publish(merchantId, "ping", "endpoint", ep.id(), Map.of("endpoint_id", ep.id(), "message", "PayCore test event"));
    }

    Optional<WebhookEndpoint> endpointById(String id) {
        return repo.endpointById(id);
    }

    private static String newSecret() {
        StringBuilder sb = new StringBuilder("whsec_");
        for (int i = 0; i < 32; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
