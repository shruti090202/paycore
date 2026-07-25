package com.paycore.auth;

import com.paycore.merchant.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates {@code /v1/**} calls from {@code Authorization: Bearer sk_test_...}.
 * The plaintext key is hashed (SHA-256) and looked up by hash; the key itself is never stored or logged.
 * No auth -> we leave the context empty and let Spring Security's entry point return 401.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";
    public static final String MDC_MERCHANT = "merchant_id";

    private final ApiKeyService apiKeyService;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)) {
            String raw = header.substring(BEARER.length()).trim();
            if (ApiKeyService.looksLikeApiKey(raw)) {
                apiKeyService.authenticate(raw).ifPresent(k -> {
                    MerchantPrincipal p = new MerchantPrincipal(k.merchantId(), k.id());
                    SecurityContextHolder.getContext().setAuthentication(
                            new MerchantAuthentication(p, MerchantAuthentication.ROLE_API));
                    MDC.put(MDC_MERCHANT, p.merchantId());
                });
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_MERCHANT);
        }
    }
}
