package com.paycore.auth;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/** Spring Security token wrapping a {@link MerchantPrincipal}. */
public class MerchantAuthentication extends AbstractAuthenticationToken {

    public static final String ROLE_DASHBOARD = "ROLE_DASHBOARD";
    public static final String ROLE_API = "ROLE_API";

    private final MerchantPrincipal principal;

    public MerchantAuthentication(MerchantPrincipal principal, String role) {
        super(List.of(new SimpleGrantedAuthority(role)));
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public MerchantPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return principal.merchantId();
    }
}
