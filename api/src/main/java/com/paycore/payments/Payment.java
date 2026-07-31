package com.paycore.payments;

import com.paycore.common.jdbc.Jsonb;
import com.paycore.common.money.Money;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * The payment aggregate. Mutable on purpose (unlike ledger rows): its status and money counters change over
 * its life. All changes go through {@link PaymentService}, which locks the row and validates transitions.
 * {@code version} gives optimistic locking as a second line of defence behind {@code SELECT ... FOR UPDATE}.
 */
@Table("payments")
public class Payment {

    @Id
    private String id;
    private String merchantId;
    private long amountMinor;
    private String currency;
    private long capturedMinor;
    private long refundedMinor;
    private String status;
    private String captureMethod;
    private String description;
    private String customerEmail;
    private String customerRef;
    private String cardBrand;
    private String cardLast4;
    private String cardFingerprint;
    private String bankRef;
    private Integer riskScore;
    private String riskDecision;
    private String failureCode;
    private String failureMessage;
    private String checkoutToken;
    private String successUrl;
    private String cancelUrl;
    private Jsonb metadata;
    @Version
    private Integer version;
    private Instant createdAt;
    private Instant updatedAt;

    Payment() {
    }

    // ---- derived helpers -------------------------------------------------------------------------------

    public PaymentStatus paymentStatus() {
        return PaymentStatus.fromWire(status);
    }

    public CaptureMethod captureMethodEnum() {
        return CaptureMethod.fromWire(captureMethod);
    }

    public Money amount() {
        return Money.of(amountMinor, currency);
    }

    public Money captured() {
        return Money.of(capturedMinor, currency);
    }

    public Money refunded() {
        return Money.of(refundedMinor, currency);
    }

    public Money refundable() {
        return captured().minus(refunded());
    }

    /** The only way status changes: validated against the state machine (the DB trigger re-validates). */
    void transitionTo(PaymentStatus next, Instant now) {
        PaymentStatus current = paymentStatus();
        if (!current.canTransitionTo(next)) {
            throw new IllegalStateException("illegal transition " + current.wire() + " -> " + next.wire());
        }
        this.status = next.wire();
        this.updatedAt = now;
    }

    // ---- accessors ---------------------------------------------------------------------------------------

    public String getId() { return id; }
    public String getMerchantId() { return merchantId; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public long getCapturedMinor() { return capturedMinor; }
    public long getRefundedMinor() { return refundedMinor; }
    public String getStatus() { return status; }
    public String getCaptureMethod() { return captureMethod; }
    public String getDescription() { return description; }
    public String getCustomerEmail() { return customerEmail; }
    public String getCustomerRef() { return customerRef; }
    public String getCardBrand() { return cardBrand; }
    public String getCardLast4() { return cardLast4; }
    public String getCardFingerprint() { return cardFingerprint; }
    public String getBankRef() { return bankRef; }
    public Integer getRiskScore() { return riskScore; }
    public String getRiskDecision() { return riskDecision; }
    public String getFailureCode() { return failureCode; }
    public String getFailureMessage() { return failureMessage; }
    public String getCheckoutToken() { return checkoutToken; }
    public String getSuccessUrl() { return successUrl; }
    public String getCancelUrl() { return cancelUrl; }
    public Jsonb getMetadata() { return metadata; }
    public Integer getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    // Package-private mutators: only the payments module changes a payment.
    void setId(String id) { this.id = id; }
    void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    void setAmountMinor(long amountMinor) { this.amountMinor = amountMinor; }
    void setCurrency(String currency) { this.currency = currency; }
    void setCapturedMinor(long capturedMinor) { this.capturedMinor = capturedMinor; }
    void setRefundedMinor(long refundedMinor) { this.refundedMinor = refundedMinor; }
    void setStatus(String status) { this.status = status; }
    void setCaptureMethod(String captureMethod) { this.captureMethod = captureMethod; }
    void setDescription(String description) { this.description = description; }
    void setCustomerEmail(String customerEmail) { this.customerEmail = customerEmail; }
    void setCustomerRef(String customerRef) { this.customerRef = customerRef; }
    void setCardBrand(String cardBrand) { this.cardBrand = cardBrand; }
    void setCardLast4(String cardLast4) { this.cardLast4 = cardLast4; }
    void setCardFingerprint(String cardFingerprint) { this.cardFingerprint = cardFingerprint; }
    void setBankRef(String bankRef) { this.bankRef = bankRef; }
    void setRiskScore(Integer riskScore) { this.riskScore = riskScore; }
    void setRiskDecision(String riskDecision) { this.riskDecision = riskDecision; }
    void setFailureCode(String failureCode) { this.failureCode = failureCode; }
    void setFailureMessage(String failureMessage) { this.failureMessage = failureMessage; }
    void setCheckoutToken(String checkoutToken) { this.checkoutToken = checkoutToken; }
    void setSuccessUrl(String successUrl) { this.successUrl = successUrl; }
    void setCancelUrl(String cancelUrl) { this.cancelUrl = cancelUrl; }
    void setMetadata(Jsonb metadata) { this.metadata = metadata; }
    void setVersion(Integer version) { this.version = version; }
    void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
