package com.paycore.risk;

/**
 * Everything a rule may look at for one checkout attempt. {@code cardNumber} is the (validated) test PAN and
 * exists only so the test-card-signal rule can recognise the documented risk cards; it is never persisted.
 */
public record RiskContext(
        String merchantId,
        String paymentId,
        long amountMinor,
        String currency,
        String cardFingerprint,
        String cardNumber,
        String customerEmail,
        String customerRef
) {
    @Override
    public String toString() {
        return "RiskContext[payment=" + paymentId + ", amount=" + amountMinor + " " + currency + "]";
    }
}
