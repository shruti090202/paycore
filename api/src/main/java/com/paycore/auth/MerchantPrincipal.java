package com.paycore.auth;

/**
 * Who is calling. Both dashboard JWTs and merchant API keys resolve to this, so controllers never
 * care which credential was used. {@code apiKeyId} is null for JWT sessions.
 */
public record MerchantPrincipal(String merchantId, String apiKeyId) {

    public boolean viaApiKey() {
        return apiKeyId != null;
    }
}
