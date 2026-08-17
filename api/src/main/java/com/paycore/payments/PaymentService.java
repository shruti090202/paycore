package com.paycore.payments;

import com.paycore.common.error.ErrorType;
import com.paycore.common.error.PayCoreException;
import com.paycore.common.id.Ids;
import com.paycore.common.jdbc.Jsonb;
import com.paycore.common.money.Currencies;
import com.paycore.common.money.Money;
import com.paycore.ledger.LedgerService;
import com.paycore.merchant.Merchant;
import com.paycore.merchant.MerchantService;
import com.paycore.webhooks.OutboxWriter;
import com.paycore.common.config.PayCoreProperties;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * All payment state changes. Each mutating method:
 * <ol>
 *   <li>locks the payment row ({@code SELECT ... FOR UPDATE}) so concurrent callers serialize,</li>
 *   <li>validates the transition in Java (fast, friendly error),</li>
 *   <li>writes the payment, its timeline event and any ledger entry in ONE transaction,</li>
 *   <li>relies on the database to re-validate (transition trigger, CHECK constraints, balanced-entry trigger).</li>
 * </ol>
 * Bank interaction is layered on top of this (the bank simulator calls back into these methods).
 */
@Service
public class PaymentService {

    private final PaymentRepository payments;
    private final PaymentEventRepository events;
    private final PaymentQueries queries;
    private final JdbcAggregateTemplate template;
    private final LedgerService ledger;
    private final MerchantService merchants;
    private final OutboxWriter outbox;
    private final String checkoutBaseUrl;
    private final Clock clock;

    public PaymentService(PaymentRepository payments, PaymentEventRepository events, PaymentQueries queries,
                          JdbcAggregateTemplate template, LedgerService ledger, MerchantService merchants,
                          OutboxWriter outbox, PayCoreProperties props, Clock clock) {
        this.payments = payments;
        this.events = events;
        this.queries = queries;
        this.template = template;
        this.ledger = ledger;
        this.merchants = merchants;
        this.outbox = outbox;
        this.checkoutBaseUrl = props.checkoutBaseUrl();
        this.clock = clock;
    }

    public record CreateCommand(String merchantId, long amountMinor, String currency, CaptureMethod captureMethod,
                                String description, String customerEmail, String customerRef, String successUrl,
                                String cancelUrl, Map<String, String> metadata) {
    }

    /** What the bank told us about a card authorization; produced by the bank simulator. */
    public record CardSummary(String brand, String last4, String fingerprint) {
    }

    // ---- create ---------------------------------------------------------------------------------------------

    @Transactional
    public Payment create(CreateCommand cmd) {
        if (!Currencies.isSupported(cmd.currency())) {
            throw PayCoreException.invalid("currency_unsupported",
                    "Unsupported currency; supported: " + Currencies.supported(), "currency");
        }
        if (cmd.amountMinor() <= 0) {
            throw PayCoreException.invalid("amount_invalid", "amount_minor must be positive", "amount_minor");
        }
        Instant now = clock.instant();
        Payment p = new Payment();
        p.setId(Ids.newId(Ids.PAYMENT));
        p.setMerchantId(cmd.merchantId());
        p.setAmountMinor(cmd.amountMinor());
        p.setCurrency(cmd.currency());
        p.setCapturedMinor(0);
        p.setRefundedMinor(0);
        p.setStatus(PaymentStatus.CREATED.wire());
        p.setCaptureMethod((cmd.captureMethod() == null ? CaptureMethod.AUTOMATIC : cmd.captureMethod()).wire());
        p.setDescription(cmd.description());
        p.setCustomerEmail(cmd.customerEmail() == null ? null : cmd.customerEmail().trim().toLowerCase(Locale.ROOT));
        p.setCustomerRef(cmd.customerRef());
        p.setSuccessUrl(cmd.successUrl());
        p.setCancelUrl(cmd.cancelUrl());
        p.setMetadata(Jsonb.of(cmd.metadata() == null ? Map.of() : cmd.metadata()));
        p.setCheckoutToken(CheckoutTokens.newToken());
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        Payment saved = template.insert(p);
        recordEvent(saved, "payment.created", null, PaymentStatus.CREATED, Map.of(
                "amount_minor", saved.getAmountMinor(), "currency", saved.getCurrency()), now);
        emit(saved, "payment.created");
        return saved;
    }

    // ---- reads ----------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Payment require(String merchantId, String paymentId) {
        return payments.findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> PayCoreException.notFound("payment", paymentId));
    }

    @Transactional(readOnly = true)
    public Payment requireByCheckoutToken(String token) {
        return payments.findByCheckoutToken(token)
                .orElseThrow(() -> PayCoreException.notFound("checkout session", token));
    }

    @Transactional(readOnly = true)
    public PaymentQueries.Page list(PaymentQueries.Filter filter) {
        return queries.list(filter);
    }

    @Transactional(readOnly = true)
    public List<PaymentEvent> timeline(String paymentId) {
        return events.findByPaymentIdOrderByIdAsc(paymentId);
    }

    // ---- state changes --------------------------------------------------------------------------------------

    /**
     * The bank approved the authorization. Called by the checkout flow. For {@code automatic}
     * capture the payment is captured in the same transaction — two edges, two events, one commit.
     */
    @Transactional
    public Payment recordAuthorization(String paymentId, String bankRef, CardSummary card) {
        Payment p = lock(paymentId);
        Instant now = clock.instant();
        PaymentStatus from = p.paymentStatus();
        requireTransition(p, PaymentStatus.AUTHORIZED, "authorize");
        p.setBankRef(bankRef);
        if (card != null) {
            p.setCardBrand(card.brand());
            p.setCardLast4(card.last4());
            p.setCardFingerprint(card.fingerprint());
        }
        p.transitionTo(PaymentStatus.AUTHORIZED, now);
        p = template.update(p);
        recordEvent(p, "payment.authorized", from, PaymentStatus.AUTHORIZED, Map.of("bank_ref", bankRef), now);
        emit(p, "payment.authorized");
        if (p.captureMethodEnum() == CaptureMethod.AUTOMATIC) {
            p = doCapture(p, p.amount(), now);
        }
        return p;
    }

    /** Merchant-initiated capture of an authorized payment (full, or partial: the remainder is released). */
    @Transactional
    public Payment capture(String merchantId, String paymentId, Long amountMinor) {
        Payment p = lockOwned(merchantId, paymentId);
        requireTransition(p, PaymentStatus.CAPTURED, "capture");
        Money toCapture = amountMinor == null ? p.amount() : Money.of(amountMinor, p.getCurrency());
        if (!toCapture.isPositive()) {
            throw PayCoreException.invalid("amount_invalid", "Capture amount must be positive", "amount_minor");
        }
        if (toCapture.isGreaterThan(p.amount())) {
            throw PayCoreException.invalid("amount_exceeds_authorized",
                    "Capture amount " + toCapture + " exceeds authorized amount " + p.amount(), "amount_minor");
        }
        return doCapture(p, toCapture, clock.instant());
    }

    /** Cancel (void) before any money moved. */
    @Transactional
    public Payment cancel(String merchantId, String paymentId) {
        Payment p = lockOwned(merchantId, paymentId);
        Instant now = clock.instant();
        PaymentStatus from = p.paymentStatus();
        requireTransition(p, PaymentStatus.CANCELED, "cancel");
        p.transitionTo(PaymentStatus.CANCELED, now);
        p = template.update(p);
        recordEvent(p, "payment.canceled", from, PaymentStatus.CANCELED, Map.of(), now);
        emit(p, "payment.canceled");
        return p;
    }

    /** The bank declined, or capture failed. Terminal. */
    @Transactional
    public Payment markFailed(String paymentId, String failureCode, String failureMessage) {
        Payment p = lock(paymentId);
        Instant now = clock.instant();
        PaymentStatus from = p.paymentStatus();
        requireTransition(p, PaymentStatus.FAILED, "fail");
        p.setFailureCode(failureCode);
        p.setFailureMessage(failureMessage);
        p.transitionTo(PaymentStatus.FAILED, now);
        p = template.update(p);
        recordEvent(p, "payment.failed", from, PaymentStatus.FAILED,
                Map.of("failure_code", failureCode, "failure_message", failureMessage == null ? "" : failureMessage), now);
        emit(p, "payment.failed");
        return p;
    }

    /**
     * We are about to ask the bank (or asked and never heard back). Committed BEFORE the bank call, with the
     * reference we will send, so a crash or timeout leaves a payment the status-check job can resolve.
     */
    @Transactional
    public Payment markPendingBank(String paymentId, String bankRef, CardSummary card) {
        Payment p = lock(paymentId);
        Instant now = clock.instant();
        PaymentStatus from = p.paymentStatus();
        requireTransition(p, PaymentStatus.PENDING_BANK, "mark pending");
        p.setBankRef(bankRef);
        if (card != null) {
            p.setCardBrand(card.brand());
            p.setCardLast4(card.last4());
            p.setCardFingerprint(card.fingerprint());
        }
        p.transitionTo(PaymentStatus.PENDING_BANK, now);
        p = template.update(p);
        recordEvent(p, "payment.pending_bank", from, PaymentStatus.PENDING_BANK, Map.of("bank_ref", bankRef), now);
        return p;
    }

    /**
     * The bank approved a refund. Updates the counters + status, posts the ledger entry and the timeline event.
     * Runs inside the caller's transaction together with the refund row update.
     */
    @Transactional
    public Payment applyRefundSuccess(String paymentId, String refundId, Money amount) {
        Payment p = lock(paymentId);
        Instant now = clock.instant();
        PaymentStatus from = p.paymentStatus();
        Money newRefunded = p.refunded().plus(amount);
        if (newRefunded.isGreaterThan(p.captured())) {
            // Cannot happen if the reservation logic is right; the DB CHECK would reject it anyway.
            throw new IllegalStateException("refund " + refundId + " would exceed captured amount");
        }
        PaymentStatus to = newRefunded.equals(p.captured()) ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
        requireTransition(p, to, "refund");
        p.setRefundedMinor(newRefunded.minor());
        p.transitionTo(to, now);
        p = template.update(p);
        ledger.postRefund(refundId, p.getId(), p.getMerchantId(), amount);
        recordEvent(p, "payment.refunded", from, to, Map.of("refund_id", refundId, "amount_minor", amount.minor(),
                "refunded_minor", newRefunded.minor()), now);
        emit(p, "payment.refunded");
        return p;
    }

    /** Timeline entries that are not status changes (refund failed/pending, bank timeout). */
    @Transactional
    public void recordInfoEvent(String paymentId, String type, Map<String, ?> data) {
        Payment p = payments.findById(paymentId).orElseThrow(() -> PayCoreException.notFound("payment", paymentId));
        recordEvent(p, type, null, null, data, clock.instant());
    }

    // ---- internals ------------------------------------------------------------------------------------------

    private Payment doCapture(Payment p, Money toCapture, Instant now) {
        PaymentStatus from = p.paymentStatus();
        Merchant merchant = merchants.require(p.getMerchantId());
        Money fee = FeePolicy.feeFor(toCapture, merchant.feeBps(), merchant.feeFixedMinor());

        p.setCapturedMinor(toCapture.minor());
        p.transitionTo(PaymentStatus.CAPTURED, now);
        p = template.update(p);
        ledger.postCapture(p.getId(), p.getMerchantId(), toCapture, fee);
        recordEvent(p, "payment.captured", from, PaymentStatus.CAPTURED, Map.of(
                "captured_minor", toCapture.minor(), "fee_minor", fee.minor(),
                "net_minor", toCapture.minus(fee).minor()), now);
        emit(p, "payment.captured");
        return p;
    }

    private Payment lockOwned(String merchantId, String paymentId) {
        return payments.lockByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> PayCoreException.notFound("payment", paymentId));
    }

    private Payment lock(String paymentId) {
        return payments.lockById(paymentId).orElseThrow(() -> PayCoreException.notFound("payment", paymentId));
    }

    private static void requireTransition(Payment p, PaymentStatus to, String verb) {
        PaymentStatus from = p.paymentStatus();
        if (!from.canTransitionTo(to)) {
            throw new PayCoreException(ErrorType.STATE_CONFLICT, "payment_state_invalid",
                    "Cannot " + verb + " a payment in status '" + from.wire() + "'"
                            + (from.isTerminal() ? " (terminal)" : ""));
        }
    }

    /** Outbox event in the same transaction: the merchant sees exactly what the API would return right now. */
    private void emit(Payment p, String type) {
        outbox.publish(p.getMerchantId(), type, "payment", p.getId(), PaymentDto.asMap(p, checkoutBaseUrl));
    }

    private void recordEvent(Payment p, String type, PaymentStatus from, PaymentStatus to, Map<String, ?> data, Instant now) {
        template.insert(new PaymentEvent(Ids.newId(Ids.EVENT), p.getId(), type,
                from == null ? null : from.wire(), to == null ? null : to.wire(), Jsonb.of(data), now));
    }
}
