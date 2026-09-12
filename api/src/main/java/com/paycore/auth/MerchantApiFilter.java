package com.paycore.auth;

import jakarta.servlet.Filter;

/** Extension point for cross-cutting filters on the merchant API (/v1/**) that need an authenticated MerchantPrincipal: rate limiting, idempotency, ... */
public interface MerchantApiFilter {

    Filter filter();

    /** Lower runs first. */
    int order();
}
