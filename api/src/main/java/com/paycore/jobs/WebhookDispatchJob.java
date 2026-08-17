package com.paycore.jobs;

import com.paycore.webhooks.WebhookDispatcher;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Fan out new outbox events and deliver due webhooks. Safe to run concurrently with the in-process scheduler. */
@Component
public class WebhookDispatchJob implements Job {

    public static final String NAME = "webhook-dispatch";

    private final WebhookDispatcher dispatcher;

    public WebhookDispatchJob(WebhookDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Map<String, Object> run() {
        // Loop while a full batch was claimed so a backlog drains in one cron trigger.
        WebhookDispatcher.Summary total = new WebhookDispatcher.Summary(0, 0, 0, 0, 0, 0);
        for (int i = 0; i < 20; i++) {
            WebhookDispatcher.Summary s = dispatcher.dispatchOnce();
            total = new WebhookDispatcher.Summary(total.fannedOut() + s.fannedOut(), total.deliveriesCreated() + s.deliveriesCreated(),
                    total.claimed() + s.claimed(), total.delivered() + s.delivered(), total.retried() + s.retried(), total.dead() + s.dead());
            if (s.claimed() == 0 && s.fannedOut() == 0) {
                break;
            }
        }
        return dispatcher.summaryToMap(total);
    }
}
