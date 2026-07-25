package com.paycore.common.error;

import org.springframework.http.HttpStatus;

/** Coarse error categories; every API error carries exactly one, mapped to an HTTP status. */
public enum ErrorType {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST),
    AUTHENTICATION_ERROR(HttpStatus.UNAUTHORIZED),
    PERMISSION_ERROR(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    CONFLICT(HttpStatus.CONFLICT),
    STATE_CONFLICT(HttpStatus.CONFLICT),
    IDEMPOTENCY_ERROR(HttpStatus.UNPROCESSABLE_CONTENT),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorType(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    /** Wire representation, e.g. {@code validation_error}. */
    public String wireName() {
        return name().toLowerCase();
    }
}
