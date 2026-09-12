package com.paycore.common.error;

/** The single exception type modules throw for expected failures (bad input, illegal state, missing rows). */
public class PayCoreException extends RuntimeException {

    private final ErrorType type;
    private final String code;
    private final String param;

    public PayCoreException(ErrorType type, String code, String message) {
        this(type, code, message, null);
    }

    public PayCoreException(ErrorType type, String code, String message, String param) {
        super(message);
        this.type = type;
        this.code = code;
        this.param = param;
    }

    public static PayCoreException notFound(String resource, String id) {
        return new PayCoreException(ErrorType.NOT_FOUND, "resource_missing",
                "No such " + resource + ": " + id);
    }

    public static PayCoreException invalid(String code, String message, String param) {
        return new PayCoreException(ErrorType.INVALID_REQUEST, code, message, param);
    }

    public static PayCoreException stateConflict(String code, String message) {
        return new PayCoreException(ErrorType.STATE_CONFLICT, code, message);
    }

    public static PayCoreException unauthenticated(String message) {
        return new PayCoreException(ErrorType.AUTHENTICATION_ERROR, "unauthenticated", message);
    }

    public ErrorType type() {
        return type;
    }

    public String code() {
        return code;
    }

    public String param() {
        return param;
    }
}
