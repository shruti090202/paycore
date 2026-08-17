package com.paycore.jobs;

import com.paycore.common.activity.ActivityGate;
import com.paycore.payments.BankResolutionService;
import com.paycore.webhooks.WebhookDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * In-process fast path: while the {@link ActivityGate} says something recently happened, dispatch webhooks and
 * resolve pending bank calls every few seconds so demos feel instant. When the window closes it stops touching
 * the database entirely (Neon can suspend). GitHub Actions cron -> /internal/jobs remains the guaranteed path.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "paycore.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ActivityGatedScheduler {

    private static final Logger log = LoggerFactory.getLogger(ActivityGatedScheduler.class);

    private final ActivityGate gate;
    private final WebhookDispatcher dispatcher;
    private final BankResolutionService bank;

    public ActivityGatedScheduler(ActivityGate gate, WebhookDispatcher dispatcher, BankResolutionService bank) {
        this.gate = gate;
        this.dispatcher = dispatcher;
        this.bank = bank;
    }

    @Scheduled(fixedDelayString = "${paycore.scheduler.interval-ms:5000}", initialDelayString = "${paycore.scheduler.interval-ms:5000}")
    public void tick() {
        if (!gate.isActive()) {
            return;
        }
        try {
            WebhookDispatcher.Summary s = dispatcher.dispatchOnce();
            if (s.claimed() > 0 || s.fannedOut() > 0) {
                log.debug("scheduler dispatch: {}", s);
                gate.touch(); // still busy: keep the window open
            }
            bank.resolvePendingPayments(50);
            bank.resolvePendingRefunds(50);
        } catch (RuntimeException e) {
            log.warn("scheduler tick failed: {}", e.toString());
        }
    }
}
