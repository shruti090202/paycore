package com.paycore.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The one error envelope every endpoint returns. */
public record ApiError(Body error) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Body(String type, String code, String message, String param, String requestId) {
    }

    public static ApiError of(ErrorType type, String code, String message, String param, String requestId) {
        return new ApiError(new Body(type.wireName(), code, message, param, requestId));
    }
}
