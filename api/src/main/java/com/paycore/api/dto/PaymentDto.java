package com.paycore.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.paycore.payments.Payment;

import java.time.Instant;
import java.util.Map;

/** Wire shape of a payment. Card data is brand + last4 only; the checkout URL is only useful while {@code created}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentDto(
        String id,
        String object,
        long amountMinor,
        String currency,
        long capturedMinor,
        long refundedMinor,
        String status,
        String captureMethod,
        String description,
        Customer customer,
        Card card,
        String bankRef,
        Failure failure,
        String checkoutUrl,
        String successUrl,
        String cancelUrl,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt
) {
    public record Customer(String email, String ref) {
    }

    public record Card(String brand, String last4) {
    }

    public record Failure(String code, String message) {
    }

    public static PaymentDto from(Payment p, String checkoutBaseUrl) {
        boolean checkoutOpen = "created".equals(p.getStatus());
        return new PaymentDto(
                p.getId(), "payment", p.getAmountMinor(), p.getCurrency(), p.getCapturedMinor(), p.getRefundedMinor(),
                p.getStatus(), p.getCaptureMethod(), p.getDescription(),
                (p.getCustomerEmail() == null && p.getCustomerRef() == null) ? null
                        : new Customer(p.getCustomerEmail(), p.getCustomerRef()),
                p.getCardLast4() == null ? null : new Card(p.getCardBrand(), p.getCardLast4()),
                p.getBankRef(),
                p.getFailureCode() == null ? null : new Failure(p.getFailureCode(), p.getFailureMessage()),
                checkoutOpen ? checkoutBaseUrl + "/checkout/" + p.getCheckoutToken() : null,
                p.getSuccessUrl(), p.getCancelUrl(),
                p.getMetadata() == null ? Map.of() : p.getMetadata().asMap(),
                p.getCreatedAt(), p.getUpdatedAt());
    }
}
