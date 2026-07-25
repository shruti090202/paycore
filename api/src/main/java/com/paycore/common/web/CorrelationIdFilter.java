package com.paycore.common.web;

import com.paycore.common.id.Ids;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Assigns every request a correlation id. Honors an incoming {@code X-Request-Id} (so a caller's id
 * threads through our logs), otherwise mints {@code req_...}. Echoed on the response and put in MDC
 * so every log line for the request carries it — which is what makes structured logs searchable.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "request_id";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String id = (incoming != null && SAFE.matcher(incoming).matches()) ? incoming : Ids.newId(Ids.REQUEST);
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        request.setAttribute(MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    public static String current(HttpServletRequest request) {
        Object attr = request.getAttribute(MDC_KEY);
        return attr == null ? MDC.get(MDC_KEY) : attr.toString();
    }
}
