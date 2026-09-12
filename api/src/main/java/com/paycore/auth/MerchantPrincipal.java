package com.paycore.auth;

/** Who is calling. */
public record MerchantPrincipal(String merchantId, String apiKeyId) {

    public boolean viaApiKey() {
        return apiKeyId != null;
    }
}
