package com.paycore.auth;

import com.paycore.common.error.ApiError;
import com.paycore.common.error.ErrorType;
import com.paycore.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Security-filter rejections happen before any controller, so {@code @RestControllerAdvice} can't see them.
 * These handlers write the same {@link ApiError} envelope so clients see one error shape everywhere.
 */
public class JsonAuthErrorHandlers {

    private final ObjectMapper mapper;

    public JsonAuthErrorHandlers(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public AuthenticationEntryPoint entryPoint() {
        return (req, res, ex) -> write(req, res, ErrorType.AUTHENTICATION_ERROR, "unauthenticated",
                "Authentication required. Merchant API: 'Authorization: Bearer sk_test_...'. Dashboard: a JWT from /dashboard/auth/login.");
    }

    public AccessDeniedHandler accessDenied() {
        return (req, res, ex) -> write(req, res, ErrorType.PERMISSION_ERROR, "forbidden",
                "You do not have access to this resource");
    }

    private void write(HttpServletRequest req, HttpServletResponse res, ErrorType type, String code, String message)
            throws IOException {
        res.setStatus(type.status().value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (type == ErrorType.AUTHENTICATION_ERROR) {
            res.setHeader("WWW-Authenticate", "Bearer");
        }
        ApiError body = ApiError.of(type, code, message, null, CorrelationIdFilter.current(req));
        res.getWriter().write(mapper.writeValueAsString(body));
    }
}
