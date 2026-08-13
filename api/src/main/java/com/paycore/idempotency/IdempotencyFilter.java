package com.paycore.idempotency;

import com.paycore.auth.MerchantAuthentication;
import com.paycore.common.error.ApiError;
import com.paycore.common.error.ErrorType;
import com.paycore.common.web.CorrelationIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;

/**
 * Implements {@code Idempotency-Key} for mutating merchant-API calls.
 * <pre>
 *   first request        -> INSERT (merchant, key) in_progress, run, store response, mark completed
 *   same key, same body  -> replay the stored response (+ Idempotent-Replayed: true)
 *   same key, other body -> 422 idempotency_key_reused
 *   same key, in flight  -> 409 idempotency_key_in_flight + Retry-After
 *   our 5xx / exception  -> claim released so the client can retry
 * </pre>
 * Concurrency is settled by the primary key, not by application locks: 300 simultaneous requests with one key
 * produce one INSERT winner and 299 duplicates.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final int MAX_KEY_LEN = 255;
    private static final int MAX_BODY_BYTES = 256 * 1024;

    private final IdempotencyStore store;
    private final ObjectMapper mapper;
    private final Clock clock;

    public IdempotencyFilter(IdempotencyStore store, ObjectMapper mapper, Clock clock) {
        this.store = store;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !MUTATING.contains(request.getMethod()) || request.getHeader(HEADER) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof MerchantAuthentication ma)) {
            chain.doFilter(request, response); // unauthenticated: security will reject; nothing to remember
            return;
        }
        String key = request.getHeader(HEADER).trim();
        if (key.isEmpty() || key.length() > MAX_KEY_LEN) {
            writeError(request, response, ErrorType.INVALID_REQUEST, "idempotency_key_invalid",
                    "Idempotency-Key must be 1-" + MAX_KEY_LEN + " characters", null);
            return;
        }
        CachedBodyRequest cached;
        try {
            cached = new CachedBodyRequest(request, MAX_BODY_BYTES);
        } catch (IllegalArgumentException e) {
            writeError(request, response, ErrorType.INVALID_REQUEST, "request_too_large", e.getMessage(), null);
            return;
        }
        String merchantId = ma.getPrincipal().merchantId();
        String path = request.getRequestURI();
        String hash = hash(request.getMethod(), path, cached.body());
        Instant now = clock.instant();

        IdempotencyStore.Claim claim = store.claim(merchantId, key, hash, request.getMethod(), path, now);
        if (claim instanceof IdempotencyStore.Existing existing) {
            IdempotencyRecord rec = existing.record();
            if (!rec.requestHash().equals(hash)) {
                writeError(request, response, ErrorType.IDEMPOTENCY_ERROR, "idempotency_key_reused",
                        "This Idempotency-Key was already used with a different request (method, path or body)", HEADER);
                return;
            }
            if (!rec.completed()) {
                response.setHeader("Retry-After", "1");
                writeError(request, response, ErrorType.CONFLICT, "idempotency_key_in_flight",
                        "A request with this Idempotency-Key is still being processed; retry shortly", HEADER);
                return;
            }
            replay(response, rec);
            return;
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        boolean completed = false;
        try {
            chain.doFilter(cached, wrapped);
            int status = wrapped.getStatus();
            if (status < 500) {
                String body = new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8);
                store.complete(merchantId, key, status, wrapped.getContentType(), body, clock.instant());
                completed = true;
            }
        } finally {
            if (!completed) {
                // Our failure, not the client's: release the key so a retry can succeed.
                try {
                    store.delete(merchantId, key);
                } catch (RuntimeException e) {
                    log.error("failed to release idempotency key for merchant {}", merchantId, e);
                }
            }
            wrapped.copyBodyToResponse();
        }
    }

    private void replay(HttpServletResponse response, IdempotencyRecord rec) throws IOException {
        response.setStatus(rec.responseStatus());
        if (rec.responseContentType() != null) {
            response.setContentType(rec.responseContentType());
        }
        response.setHeader(REPLAYED_HEADER, "true");
        if (rec.responseBody() != null) {
            response.getWriter().write(rec.responseBody());
        }
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response, ErrorType type, String code,
                            String message, String param) throws IOException {
        response.setStatus(type.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(mapper.writeValueAsString(
                ApiError.of(type, code, message, param, CorrelationIdFilter.current(request))));
    }

    static String hash(String method, String path, byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(method.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(path.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(body);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
