package com.paycore.auth;

import jakarta.servlet.Filter;

/**
 * Extension point for cross-cutting filters on the merchant API ({@code /v1/**}) that need an authenticated
 * {@link MerchantPrincipal}: rate limiting, idempotency, ... They run after API-key authentication, in
 * ascending {@link #order()}. Modules contribute a bean; {@code auth} never depends on them.
 */
public interface MerchantApiFilter {

    Filter filter();

    /** Lower runs first. */
    int order();
}
