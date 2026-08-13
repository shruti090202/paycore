package com.paycore.idempotency;

import java.time.Instant;

public record IdempotencyRecord(
        String merchantId,
        String idemKey,
        String requestHash,
        String method,
        String path,
        String status,
        Integer responseStatus,
        String responseContentType,
        String responseBody,
        Instant createdAt,
        Instant completedAt,
        Instant expiresAt
) {
    public static final String IN_PROGRESS = "in_progress";
    public static final String COMPLETED = "completed";

    public boolean completed() {
        return COMPLETED.equals(status);
    }
}
