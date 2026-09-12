package com.paycore.payments;

import com.paycore.banksim.BankGateway;
import com.paycore.banksim.BankTimeoutException;
import com.paycore.common.error.ErrorType;
import com.paycore.common.error.PayCoreException;
import com.paycore.common.id.Ids;
import com.paycore.common.money.Money;
import com.paycore.webhooks.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Refunds use the same two-transaction shape as checkout, with one addition: the amount is RESERVED in T1. */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final BankAttemptRepository attempts;
    private final PaymentService paymentService;
    private final BankGateway bank;
    private final OutboxWriter outbox;
    private final JdbcAggregateTemplate template;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RefundService(PaymentRepository payments, RefundRepository refunds, BankAttemptRepository attempts,
                         PaymentService paymentService, BankGateway bank, OutboxWriter outbox,
                         JdbcAggregateTemplate template, PlatformTransactionManager txManager, Clock clock) {
        this.payments = payments;
        this.refunds = refunds;
        this.attempts = attempts;
        this.paymentService = paymentService;
        this.bank = bank;
        this.outbox = outbox;
        this.template = template;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    public Refund create(String merchantId, String paymentId, Long amountMinor, String reason) {
        // T1: reserve
        record Prepared(Refund refund, String attemptId, String parentBankRef) {
        }
        Prepared prep = tx.execute(status -> {
            Payment p = payments.lockByIdAndMerchantId(paymentId, merchantId)
                    .orElseThrow(() -> PayCoreException.notFound("payment", paymentId));
            if (!p.paymentStatus().isCaptured()) {
                throw new PayCoreException(ErrorType.STATE_CONFLICT, "payment_not_captured",
                        "Only captured payments can be refunded (payment is " + p.getStatus() + ")");
            }
            Money pending = Money.of(refunds.sumPending(p.getId()), p.getCurrency());
            Money refundable = p.refundable().minus(pending);
            if (!refundable.isPositive()) {
                throw new PayCoreException(ErrorType.STATE_CONFLICT, "payment_fully_refunded",
                        "Nothing left to refund on this payment" + (pending.isPositive() ? " (refunds pending)" : ""));
            }
            Money amount = amountMinor == null ? refundable : Money.of(amountMinor, p.getCurrency());
            if (!amount.isPositive()) {
                throw PayCoreException.invalid("amount_invalid", "Refund amount must be positive", "amount_minor");
            }
            if (amount.isGreaterThan(refundable)) {
                throw PayCoreException.invalid("amount_exceeds_refundable",
                        "Refund amount " + amount + " exceeds refundable amount " + refundable, "amount_minor");
            }
            Instant now = clock.instant();
            String bankRef = Ids.newId("bref");
            Refund r = template.insert(new Refund(Ids.newId(Ids.REFUND), p.getId(), merchantId, amount.minor(),
                    p.getCurrency(), Refund.PENDING, reason, bankRef, null, now, now));
            BankAttempt attempt = template.insert(new BankAttempt(Ids.newId("batt"), p.getId(), r.id(),
                    BankAttempt.KIND_REFUND, bankRef, amount.minor(), p.getCurrency(), BankAttempt.IN_FLIGHT,
                    null, null, now, null, null));
            paymentService.recordInfoEvent(p.getId(), "refund.created",
                    Map.of("refund_id", r.id(), "amount_minor", amount.minor()));
            outbox.publish(merchantId, "refund.created", "refund", r.id(), RefundDto.asMap(r));
            return new Prepared(r, attempt.id(), p.getBankRef());
        });

        // Bank call
        Refund r = prep.refund();
        Instant started = clock.instant();
        BankGateway.BankResponse response;
        try {
            response = bank.refund(new BankGateway.RefundRequest(r.bankRef(), prep.parentBankRef(), r.amountMinor(), r.currency()));
        } catch (RuntimeException e) {
            tx.executeWithoutResult(s -> attempts.recordOutcome(prep.attemptId(), BankAttempt.ERROR, null,
                    (int) Duration.between(started, clock.instant()).toMillis()));
            throw e;
        } catch (BankTimeoutException e) {
            int latency = (int) Duration.between(started, clock.instant()).toMillis();
            log.warn("bank timeout refund={} bank_ref={}", r.id(), r.bankRef());
            tx.executeWithoutResult(s -> {
                attempts.recordOutcome(prep.attemptId(), BankAttempt.TIMEOUT, null, latency);
                paymentService.recordInfoEvent(r.paymentId(), "refund.pending_bank",
                        Map.of("refund_id", r.id(), "bank_ref", r.bankRef()));
            });
            return r; // still pending; BankResolutionService finishes it
        }
        int latency = (int) Duration.between(started, clock.instant()).toMillis();

        // T2: apply
        return tx.execute(status -> {
            attempts.recordOutcome(prep.attemptId(), response.approved() ? BankAttempt.APPROVED : BankAttempt.DECLINED,
                    response.declineCode(), latency);
            return applyOutcome(r.id(), response.approved(), response.declineCode());
        });
    }

    /** Finalizes a pending refund. */
    @Transactional
    public Refund applyOutcome(String refundId, boolean approved, String declineCode) {
        Refund r = refunds.findById(refundId).orElseThrow(() -> PayCoreException.notFound("refund", refundId));
        // Lock order: payment first, always (same as T1), then the refund row.
        payments.lockById(r.paymentId());
        r = refunds.findById(refundId).orElseThrow();
        if (!Refund.PENDING.equals(r.status())) {
            return r;
        }
        Instant now = clock.instant();
        if (approved) {
            Refund done = template.update(r.withStatus(Refund.SUCCEEDED, null, now));
            paymentService.applyRefundSuccess(r.paymentId(), r.id(), Money.of(r.amountMinor(), r.currency()));
            outbox.publish(r.merchantId(), "refund.succeeded", "refund", r.id(), RefundDto.asMap(done));
            return done;
        }
        Refund failed = template.update(r.withStatus(Refund.FAILED, declineCode, now));
        outbox.publish(r.merchantId(), "refund.failed", "refund", r.id(), RefundDto.asMap(failed));
        paymentService.recordInfoEvent(r.paymentId(), "refund.failed",
                Map.of("refund_id", r.id(), "failure_code", declineCode == null ? "unknown" : declineCode));
        return failed;
    }

    @Transactional(readOnly = true)
    public Refund require(String merchantId, String refundId) {
        return refunds.findByIdAndMerchantId(refundId, merchantId)
                .orElseThrow(() -> PayCoreException.notFound("refund", refundId));
    }

    @Transactional(readOnly = true)
    public List<Refund> forPayment(String paymentId) {
        return refunds.findByPaymentIdOrderByIdAsc(paymentId);
    }
}
