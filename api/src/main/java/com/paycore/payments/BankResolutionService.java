package com.paycore.payments;

import com.paycore.banksim.BankGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Resolves the "we don't know" states by asking the bank for the reference we generated: payments stuck in pending_bank (bank timeout, or a crash. */
@Service
public class BankResolutionService {

    private static final Logger log = LoggerFactory.getLogger(BankResolutionService.class);

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final BankAttemptRepository attempts;
    private final PaymentService paymentService;
    private final RefundService refundService;
    private final BankGateway bank;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration minAge;
    private final Duration notReceivedGrace;

    public record Summary(int scanned, int approved, int declined, int notFound, int stillPending) {
    }

    public BankResolutionService(PaymentRepository payments, RefundRepository refunds, BankAttemptRepository attempts,
                                 PaymentService paymentService, RefundService refundService, BankGateway bank,
                                 PlatformTransactionManager txManager, Clock clock,
                                 org.springframework.core.env.Environment env) {
        this.payments = payments;
        this.refunds = refunds;
        this.attempts = attempts;
        this.paymentService = paymentService;
        this.refundService = refundService;
        this.bank = bank;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        // How long a pending item must be untouched before we ask the bank (lets an in-flight call finish first).
        this.minAge = Duration.ofSeconds(env.getProperty("paycore.bank.resolve-min-age-seconds", Long.class, 30L));
        // How long "no record at the bank" must persist before we conclude the request was lost.
        this.notReceivedGrace = Duration.ofSeconds(env.getProperty("paycore.bank.not-received-grace-seconds", Long.class, 600L));
    }

    public Summary resolvePendingPayments(int limit) {
        Instant now = clock.instant();
        int approved = 0, declined = 0, notFound = 0, still = 0;
        var stale = payments.findStalePendingBank(now.minus(minAge), limit);
        for (Payment p : stale) {
            try {
                switch (resolvePayment(p.getId())) {
                    case "approved" -> approved++;
                    case "declined" -> declined++;
                    case "not_found" -> notFound++;
                    default -> still++;
                }
            } catch (RuntimeException e) {
                log.error("failed to resolve payment {}", p.getId(), e);
                still++;
            }
        }
        return new Summary(stale.size(), approved, declined, notFound, still);
    }

    public Summary resolvePendingRefunds(int limit) {
        Instant now = clock.instant();
        int approved = 0, declined = 0, notFound = 0, still = 0;
        var stale = refunds.findStalePending(now.minus(minAge), limit);
        for (Refund r : stale) {
            try {
                switch (resolveRefund(r.id())) {
                    case "approved" -> approved++;
                    case "declined" -> declined++;
                    case "not_found" -> notFound++;
                    default -> still++;
                }
            } catch (RuntimeException e) {
                log.error("failed to resolve refund {}", r.id(), e);
                still++;
            }
        }
        return new Summary(stale.size(), approved, declined, notFound, still);
    }

    /** Returns approved | declined | not_found | pending. Safe to call for any payment; non-pending ones are skipped. */
    public String resolvePayment(String paymentId) {
        Payment p = payments.findById(paymentId).orElse(null);
        if (p == null || p.paymentStatus() != PaymentStatus.PENDING_BANK || p.getBankRef() == null) {
            return "skipped";
        }
        Optional<BankGateway.BankTransactionStatus> at = bank.lookup(p.getBankRef());
        Optional<BankAttempt> attempt = attempts.findByBankRef(p.getBankRef());
        Instant now = clock.instant();
        if (at.isPresent()) {
            boolean ok = at.get().outcome() == BankGateway.Outcome.APPROVED;
            tx.executeWithoutResult(s -> {
                attempt.ifPresent(a -> attempts.recordResolution(a.id(), ok ? "approved" : "declined", now));
                if (ok) {
                    paymentService.recordAuthorization(p.getId(), p.getBankRef(), null);
                } else {
                    paymentService.markFailed(p.getId(), at.get().declineCode(), DeclineCodes.message(at.get().declineCode()));
                }
            });
            log.info("resolved payment {} via bank lookup: {}", p.getId(), ok ? "approved" : "declined");
            return ok ? "approved" : "declined";
        }
        Instant since = attempt.map(BankAttempt::createdAt).orElse(p.getUpdatedAt());
        if (Duration.between(since, now).compareTo(notReceivedGrace) >= 0) {
            tx.executeWithoutResult(s -> {
                attempt.ifPresent(a -> attempts.recordResolution(a.id(), "not_found", now));
                paymentService.markFailed(p.getId(), "bank_unreachable", DeclineCodes.message("bank_unreachable"));
            });
            log.info("resolved payment {}: bank has no record after grace period -> failed", p.getId());
            return "not_found";
        }
        return "pending";
    }

    public String resolveRefund(String refundId) {
        Refund r = refunds.findById(refundId).orElse(null);
        if (r == null || !Refund.PENDING.equals(r.status()) || r.bankRef() == null) {
            return "skipped";
        }
        Optional<BankGateway.BankTransactionStatus> at = bank.lookup(r.bankRef());
        Optional<BankAttempt> attempt = attempts.findByBankRef(r.bankRef());
        Instant now = clock.instant();
        if (at.isPresent()) {
            boolean ok = at.get().outcome() == BankGateway.Outcome.APPROVED;
            tx.executeWithoutResult(s -> {
                attempt.ifPresent(a -> attempts.recordResolution(a.id(), ok ? "approved" : "declined", now));
                refundService.applyOutcome(r.id(), ok, at.get().declineCode());
            });
            return ok ? "approved" : "declined";
        }
        if (Duration.between(r.createdAt(), now).compareTo(notReceivedGrace) >= 0) {
            tx.executeWithoutResult(s -> {
                attempt.ifPresent(a -> attempts.recordResolution(a.id(), "not_found", now));
                refundService.applyOutcome(r.id(), false, "bank_unreachable");
                paymentService.recordInfoEvent(r.paymentId(), "refund.bank_unreachable", Map.of("refund_id", r.id()));
            });
            return "not_found";
        }
        return "pending";
    }
}
