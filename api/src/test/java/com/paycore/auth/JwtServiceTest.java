package com.paycore.auth;

import com.paycore.common.config.PayCoreProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-unit-test-secret-unit-test-secret";

    private static JwtService service(Clock clock, int ttlMinutes) {
        PayCoreProperties props = new PayCoreProperties(new PayCoreProperties.Jwt(SECRET, ttlMinutes),
                "k", "k", "t", "http://x", "http://x", null);
        return new JwtService(props, clock);
    }

    @Test
    void issuedTokenRoundTripsWithSubjectAndScope() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        JwtService svc = service(Clock.fixed(now, ZoneOffset.UTC), 30);

        JwtService.IssuedToken issued = svc.issueDashboardToken("mer_123");
        Jwt jwt = svc.decoder().decode(issued.token());

        assertThat(jwt.getSubject()).isEqualTo("mer_123");
        assertThat(jwt.getClaimAsString(JwtService.SCOPE_CLAIM)).isEqualTo(JwtService.SCOPE_DASHBOARD);
        assertThat(issued.expiresAt()).isEqualTo(now.plus(Duration.ofMinutes(30)));
    }

    @Test
    void expiredTokenIsRejected() {
        Instant past = Instant.now().minus(Duration.ofHours(2));
        JwtService issuer = service(Clock.fixed(past, ZoneOffset.UTC), 30);
        JwtService verifier = service(Clock.systemUTC(), 30);

        String token = issuer.issueDashboardToken("mer_123").token();

        assertThatThrownBy(() -> verifier.decoder().decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedWithDifferentSecretIsRejected() {
        JwtService a = service(Clock.systemUTC(), 30);
        PayCoreProperties other = new PayCoreProperties(
                new PayCoreProperties.Jwt("another-secret-another-secret-another-secret-00", 30),
                "k", "k", "t", "http://x", "http://x", null);
        JwtService b = new JwtService(other, Clock.systemUTC());

        String token = a.issueDashboardToken("mer_123").token();

        assertThatThrownBy(() -> b.decoder().decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void shortSecretIsRefusedAtStartup() {
        PayCoreProperties props = new PayCoreProperties(new PayCoreProperties.Jwt("too-short", 30),
                "k", "k", "t", "http://x", "http://x", null);
        assertThatThrownBy(() -> new JwtService(props, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
    }
}
