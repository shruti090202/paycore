package com.paycore.payments;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefundDto(String id, String object, String paymentId, long amountMinor, String currency, String status,
                        String reason, String failureCode, Instant createdAt, Instant updatedAt) {

    public static java.util.Map<String, Object> asMap(Refund r) {
        return com.paycore.common.jdbc.Jsonb.of(from(r)).asMap();
    }

    public static RefundDto from(Refund r) {
        return new RefundDto(r.id(), "refund", r.paymentId(), r.amountMinor(), r.currency(), r.status(), r.reason(),
                r.failureCode(), r.createdAt(), r.updatedAt());
    }
}
