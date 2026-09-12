package com.paycore.payments;

import com.paycore.banksim.BankGateway;
import com.paycore.banksim.BankTimeoutException;
import com.paycore.banksim.CardFingerprints;
import com.paycore.banksim.TestCards;
import com.paycore.common.error.ErrorType;
import com.paycore.common.error.PayCoreException;
import com.paycore.common.id.Ids;
import com.paycore.risk.RiskContext;
import com.paycore.risk.RiskDecision;
import com.paycore.risk.RiskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Hosted-checkout confirmation: the one place card data is handled. */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);
    static final Duration SESSION_TTL = Duration.ofHours(24);

    private final PaymentRepository payments;
    private final PaymentService paymentService;
    private final BankAttemptRepository attempts;
    private final BankGateway bank;
    private final CardFingerprints fingerprints;
    private final RiskService risk;
    private final JdbcAggregateTemplate template;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CheckoutService(PaymentRepository payments, PaymentService paymentService, BankAttemptRepository attempts,
                           BankGateway bank, CardFingerprints fingerprints, RiskService risk, JdbcAggregateTemplate template,
                           PlatformTransactionManager txManager, Clock clock) {
        this.payments = payments;
        this.paymentService = paymentService;
        this.attempts = attempts;
        this.bank = bank;
        this.fingerprints = fingerprints;
        this.risk = risk;
        this.template = template;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    public enum ResultKind { APPROVED, DECLINED, PENDING }

    public record ConfirmResult(ResultKind kind, Payment payment, String declineCode, String redirectUrl) {
    }

    public Payment session(String token) {
        Payment p = paymentService.requireByCheckoutToken(token);
        if (p.paymentStatus() == PaymentStatus.CREATED && isExpired(p)) {
            throw new PayCoreException(ErrorType.STATE_CONFLICT, "checkout_session_expired", "This checkout session has expired");
        }
        return p;
    }

    public ConfirmResult confirm(String token, BankGateway.CardDetails card) {
        TestCards.TestCard testCard = CardValidator.validate(card, clock);
        String fingerprint = fingerprints.fingerprint(testCard.number());
        PaymentService.CardSummary summary = new PaymentService.CardSummary(testCard.brand(), testCard.last4(), fingerprint);

        // Risk (Redis velocity counters etc.) is evaluated before any transaction is opened.
        Payment preview = paymentService.requireByCheckoutToken(token);
        RiskDecision decision = risk.evaluate(new RiskContext(preview.getMerchantId(), preview.getId(), preview.getAmountMinor(),
                preview.getCurrency(), fingerprint, testCard.number(), preview.getCustomerEmail(), preview.getCustomerRef()));

        // T1
        record Prepared(String paymentId, String attemptId, String bankRef, long amount, String currency, boolean blocked) {
        }
        Prepared prep = tx.execute(status -> {
            Payment p = payments.lockByCheckoutToken(token)
                    .orElseThrow(() -> PayCoreException.notFound("checkout session", token));
            if (p.paymentStatus() == PaymentStatus.PENDING_BANK) {
                throw new PayCoreException(ErrorType.STATE_CONFLICT, "checkout_in_progress",
                        "This payment is already being confirmed with the bank");
            }
            if (p.paymentStatus() != PaymentStatus.CREATED) {
                throw new PayCoreException(ErrorType.STATE_CONFLICT, "checkout_session_closed",
                        "This checkout session is no longer open (payment is " + p.getStatus() + ")");
            }
            if (isExpired(p)) {
                throw new PayCoreException(ErrorType.STATE_CONFLICT, "checkout_session_expired", "This checkout session has expired");
            }
            paymentService.recordRiskDecision(p.getId(), decision, summary);
            risk.record(p.getId(), p.getMerchantId(), decision);
            if (decision.outcome() == RiskDecision.Outcome.BLOCK) {
                // Refused before the bank is ever contacted.
                paymentService.markFailed(p.getId(), "risk_blocked", DeclineCodes.message("fraudulent"));
                return new Prepared(p.getId(), null, null, p.getAmountMinor(), p.getCurrency(), true);
            }
            String bankRef = Ids.newId("bref");
            BankAttempt attempt = template.insert(new BankAttempt(Ids.newId("batt"), p.getId(), null,
                    BankAttempt.KIND_AUTHORIZE, bankRef, p.getAmountMinor(), p.getCurrency(), BankAttempt.IN_FLIGHT,
                    null, null, clock.instant(), null, null));
            paymentService.markPendingBank(p.getId(), bankRef, summary);
            return new Prepared(p.getId(), attempt.id(), bankRef, p.getAmountMinor(), p.getCurrency(), false);
        });
        if (prep.blocked()) {
            return new ConfirmResult(ResultKind.DECLINED, payments.findById(prep.paymentId()).orElseThrow(), "card_declined", null);
        }

        // Bank call (no transaction)
        Instant started = clock.instant();
        BankGateway.BankResponse response;
        // Send the normalized PAN (validation stripped spaces/dashes), never the raw user input.
        BankGateway.CardDetails normalized = new BankGateway.CardDetails(testCard.number(), card.expMonth(), card.expYear(), card.cvc());
        try {
            response = bank.authorize(new BankGateway.AuthorizeRequest(prep.bankRef(), prep.amount(), prep.currency(), normalized));
        } catch (RuntimeException e) {
            // Unexpected failure talking to the bank: the payment stays pending_bank (resolved by the job), but the attempt must not look "in flight" forever.
            tx.executeWithoutResult(s -> attempts.recordOutcome(prep.attemptId(), BankAttempt.ERROR, null,
                    (int) Duration.between(started, clock.instant()).toMillis()));
            throw e;
        } catch (BankTimeoutException e) {
            int latency = (int) Duration.between(started, clock.instant()).toMillis();
            log.warn("bank timeout payment={} bank_ref={}", prep.paymentId(), prep.bankRef());
            tx.executeWithoutResult(s -> {
                attempts.recordOutcome(prep.attemptId(), BankAttempt.TIMEOUT, null, latency);
                paymentService.recordInfoEvent(prep.paymentId(), "bank.timeout",
                        Map.of("bank_ref", prep.bankRef(), "latency_ms", latency));
            });
            Payment p = payments.findById(prep.paymentId()).orElseThrow();
            return new ConfirmResult(ResultKind.PENDING, p, null, null);
        }
        int latency = (int) Duration.between(started, clock.instant()).toMillis();

        // T2
        return tx.execute(status -> {
            if (response.approved()) {
                attempts.recordOutcome(prep.attemptId(), BankAttempt.APPROVED, null, latency);
                Payment p = paymentService.recordAuthorization(prep.paymentId(), prep.bankRef(), summary);
                return new ConfirmResult(ResultKind.APPROVED, p, null, successRedirect(p));
            }
            attempts.recordOutcome(prep.attemptId(), BankAttempt.DECLINED, response.declineCode(), latency);
            Payment p = paymentService.markFailed(prep.paymentId(), response.declineCode(),
                    DeclineCodes.message(response.declineCode()));
            return new ConfirmResult(ResultKind.DECLINED, p, response.declineCode(), null);
        });
    }

    /** Shopper abandons checkout. */
    public Payment cancel(String token) {
        return tx.execute(status -> {
            Payment p = payments.lockByCheckoutToken(token)
                    .orElseThrow(() -> PayCoreException.notFound("checkout session", token));
            if (p.paymentStatus() != PaymentStatus.CREATED) {
                throw new PayCoreException(ErrorType.STATE_CONFLICT, "checkout_session_closed",
                        "This checkout session is no longer open (payment is " + p.getStatus() + ")");
            }
            return paymentService.cancel(p.getMerchantId(), p.getId());
        });
    }

    public static String successRedirect(Payment p) {
        if (p.getSuccessUrl() == null) {
            return null;
        }
        String sep = p.getSuccessUrl().contains("?") ? "&" : "?";
        return p.getSuccessUrl() + sep + "payment_id=" + p.getId();
    }

    private boolean isExpired(Payment p) {
        return p.getCreatedAt().plus(SESSION_TTL).isBefore(clock.instant());
    }
}
