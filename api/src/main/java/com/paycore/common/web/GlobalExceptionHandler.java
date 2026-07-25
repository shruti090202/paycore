package com.paycore.common.web;

import com.paycore.common.error.ApiError;
import com.paycore.common.error.ErrorType;
import com.paycore.common.error.PayCoreException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps every exception to the {@link ApiError} envelope. Internal errors never leak messages or stack traces. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(PayCoreException.class)
    public ResponseEntity<ApiError> handle(PayCoreException ex, HttpServletRequest req) {
        return respond(ex.type(), ex.code(), ex.getMessage(), ex.param(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handle(MethodArgumentNotValidException ex, HttpServletRequest req) {
        FieldError fe = ex.getBindingResult().getFieldError();
        String param = fe == null ? null : snake(fe.getField());
        String msg = fe == null ? "Validation failed" : fe.getDefaultMessage();
        return respond(ErrorType.VALIDATION_ERROR, "parameter_invalid", msg, param, req);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handle(ConstraintViolationException ex, HttpServletRequest req) {
        return respond(ErrorType.VALIDATION_ERROR, "parameter_invalid", ex.getMessage(), null, req);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleUnreadable(Exception ex, HttpServletRequest req) {
        return respond(ErrorType.INVALID_REQUEST, "malformed_request",
                "Request body or parameter could not be parsed", null, req);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handle(MissingRequestHeaderException ex, HttpServletRequest req) {
        return respond(ErrorType.INVALID_REQUEST, "header_missing",
                "Missing required header: " + ex.getHeaderName(), ex.getHeaderName(), req);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handle(AuthenticationException ex, HttpServletRequest req) {
        return respond(ErrorType.AUTHENTICATION_ERROR, "unauthenticated", "Authentication required", null, req);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handle(AccessDeniedException ex, HttpServletRequest req) {
        return respond(ErrorType.PERMISSION_ERROR, "forbidden", "You do not have access to this resource", null, req);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handle(NoResourceFoundException ex, HttpServletRequest req) {
        return respond(ErrorType.NOT_FOUND, "route_missing", "No such route", null, req);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handle(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        ApiError body = ApiError.of(ErrorType.INVALID_REQUEST, "method_not_allowed", "Method not allowed", null,
                CorrelationIdFilter.current(req));
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception request_id={}", CorrelationIdFilter.current(req), ex);
        return respond(ErrorType.INTERNAL_ERROR, "internal_error",
                "Something went wrong on our side. Quote the request_id when contacting support.", null, req);
    }

    private ResponseEntity<ApiError> respond(ErrorType type, String code, String message, String param,
                                             HttpServletRequest req) {
        ApiError body = ApiError.of(type, code, message, param, CorrelationIdFilter.current(req));
        return ResponseEntity.status(type.status()).body(body);
    }

    private static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
