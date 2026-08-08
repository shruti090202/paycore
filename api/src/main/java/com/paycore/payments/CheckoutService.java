package com.paycore.payments;

import com.paycore.banksim.BankGateway;
import com.paycore.banksim.BankTimeoutException;
import com.paycore.banksim.CardFingerprints;
import com.paycore.banksim.TestCards;
import com.paycore.common.error.ErrorType;
import com.paycore.common.error.PayCoreException;
import com.paycore.common.id.Ids;
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

/**
 * Hosted-checkout confirmation: the one place card data is handled.
 * <p>
 * The bank is called OUTSIDE any database transaction, between two short ones:
 * <ol>
 *   <li>T1: lock the payment, generate the bank reference, record the attempt, move to {@code pending_bank}, COMMIT.</li>
 *   <li>Call the bank (no locks held; the bank can take seconds).</li>
 *   <li>T2: apply the outcome (authorize/capture or fail). On timeout nothing changes: the payment stays
 *       {@code pending_bank} with a reference, and {@link BankResolutionService} asks the bank later.</li>
 * </ol>
 * A crash between T1 and T2 leaves exactly the same state as a timeout, so there is a single recovery path.
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);
    static final Duration SESSION_TTL = Duration.ofHours(24);

    private final PaymentRepository payments;
    private final PaymentService paymentService;
    private final BankAttemptRepository attempts;
    private final BankGateway bank;
    private final CardFingerprints fingerprints;
    private final JdbcAggregateTemplate template;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CheckoutService(PaymentRepository payments, PaymentService paymentService, BankAttemptRepository attempts,
                           BankGateway bank, CardFingerprints fingerprints, JdbcAggregateTemplate template,
                           PlatformTransactionManager txManager, Clock clock) {
        this.payments = payments;
        this.paymentService = paymentService;
        this.attempts = attempts;
        this.bank = bank;
        this.fingerprints = fingerprints;
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

        // T1 -----------------------------------------------------------------------------------------------
        record Prepared(String paymentId, String attemptId, String bankRef, long amount, String currency) {
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
            String bankRef = Ids.newId("bref");
            BankAttempt attempt = template.insert(new BankAttempt(Ids.newId("batt"), p.getId(), null,
                    BankAttempt.KIND_AUTHORIZE, bankRef, p.getAmountMinor(), p.getCurrency(), BankAttempt.IN_FLIGHT,
                    null, null, clock.instant(), null, null));
            paymentService.markPendingBank(p.getId(), bankRef, summary);
            return new Prepared(p.getId(), attempt.id(), bankRef, p.getAmountMinor(), p.getCurrency());
        });

        // Bank call (no transaction) -----------------------------------------------------------------------
        Instant started = clock.instant();
        BankGateway.BankResponse response;
        // Send the normalized PAN (validation stripped spaces/dashes), never the raw user input.
        BankGateway.CardDetails normalized = new BankGateway.CardDetails(testCard.number(), card.expMonth(), card.expYear(), card.cvc());
        try {
            response = bank.authorize(new BankGateway.AuthorizeRequest(prep.bankRef(), prep.amount(), prep.currency(), normalized));
        } catch (RuntimeException e) {
            // Unexpected failure talking to the bank: the payment stays pending_bank (resolved by the job), but the
            // attempt must not look "in flight" forever.
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

        // T2 -----------------------------------------------------------------------------------------------
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
