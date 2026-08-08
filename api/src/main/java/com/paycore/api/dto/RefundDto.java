package com.paycore.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.paycore.payments.Refund;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefundDto(String id, String object, String paymentId, long amountMinor, String currency, String status,
                        String reason, String failureCode, Instant createdAt, Instant updatedAt) {

    public static RefundDto from(Refund r) {
        return new RefundDto(r.id(), "refund", r.paymentId(), r.amountMinor(), r.currency(), r.status(), r.reason(),
                r.failureCode(), r.createdAt(), r.updatedAt());
    }
}
