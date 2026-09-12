package com.paycore.risk;

/** Everything a rule may look at for one checkout attempt. */
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
