package com.paycore.webhooks;

import com.paycore.common.activity.ActivityGate;
import com.paycore.common.id.Ids;
import com.paycore.common.jdbc.Jsonb;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The producer side of the outbox. MANDATORY propagation: an event may only be written from inside the
 * transaction that changes state, so "state changed but event lost" and "event sent but state rolled back"
 * are both impossible — the two rows commit or vanish together.
 */
@Service
public class OutboxWriter {

    private final WebhookRepository repo;
    private final ActivityGate activity;
    private final Clock clock;

    public OutboxWriter(WebhookRepository repo, ActivityGate activity, Clock clock) {
        this.repo = repo;
        this.activity = activity;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent publish(String merchantId, String type, String aggregateType, String aggregateId, Map<String, ?> object) {
        Instant now = clock.instant();
        String id = Ids.newId(Ids.EVENT);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("object", "event");
        body.put("type", type);
        body.put("created", now.toString());
        body.put("livemode", false);
        body.put("data", Map.of("object", object));
        OutboxEvent e = new OutboxEvent(id, merchantId, type, aggregateType, aggregateId, Jsonb.of(body), now, null);
        repo.insertEvent(e);
        activity.touch(); // wake the in-process scheduler for a bounded window
        return e;
    }
}
