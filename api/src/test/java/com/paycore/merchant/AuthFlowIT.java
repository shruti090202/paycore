package com.paycore.merchant;

import com.paycore.support.AbstractIntegrationTest;
import com.paycore.support.Api;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthFlowIT extends AbstractIntegrationTest {

    @Test
    void signupIssuesTokenAndTokenOpensDashboard() {
        Api api = api();
        String email = Api.uniqueEmail();

        Api.Response signup = api.post("/dashboard/auth/signup",
                Map.of("name", "Acme", "email", email, "password", "correct-horse-battery"), Api.none());

        assertThat(signup.status()).isEqualTo(201);
        assertThat(signup.text("/token")).isNotBlank();
        assertThat(signup.text("/merchant/id")).startsWith("mer_");
        assertThat(signup.text("/merchant/email")).isEqualTo(email);
        assertThat(signup.at("/merchant/password_hash").isMissingNode()).as("hash never serialized").isTrue();
        assertThat(signup.at("/merchant/fee_bps").asInt()).isEqualTo(200);

        Api.Response me = api.get("/dashboard/me", Api.bearer(signup.text("/token")));
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.text("/id")).isEqualTo(signup.text("/merchant/id"));
    }

    @Test
    void loginRejectsWrongPasswordWithErrorEnvelope() {
        Api api = api();
        String email = Api.uniqueEmail();
        api.post("/dashboard/auth/signup", Map.of("name", "Acme", "email", email, "password", "correct-horse-battery"), Api.none());

        Api.Response bad = api.post("/dashboard/auth/login", Map.of("email", email, "password", "wrong-password"), Api.none());
        assertThat(bad.status()).isEqualTo(401);
        assertThat(bad.text("/error/type")).isEqualTo("authentication_error");
        assertThat(bad.text("/error/code")).isEqualTo("unauthenticated");
        assertThat(bad.text("/error/request_id")).startsWith("req_");
        assertThat(bad.headers().getFirst("X-Request-Id")).isEqualTo(bad.text("/error/request_id"));

        Api.Response unknown = api.post("/dashboard/auth/login", Map.of("email", "nobody@example.test", "password", "x"), Api.none());
        assertThat(unknown.status()).isEqualTo(401);
        assertThat(unknown.text("/error/message")).as("same message for unknown email and wrong password")
                .isEqualTo(bad.text("/error/message"));

        Api.Response good = api.post("/dashboard/auth/login", Map.of("email", email, "password", "correct-horse-battery"), Api.none());
        assertThat(good.status()).isEqualTo(200);
        assertThat(good.text("/token")).isNotBlank();
    }

    @Test
    void duplicateEmailIsConflict() {
        Api api = api();
        String email = Api.uniqueEmail();
        Map<String, String> body = Map.of("name", "Acme", "email", email, "password", "correct-horse-battery");
        assertThat(api.post("/dashboard/auth/signup", body, Api.none()).status()).isEqualTo(201);

        Api.Response dup = api.post("/dashboard/auth/signup", body, Api.none());
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.text("/error/code")).isEqualTo("email_taken");
        assertThat(dup.text("/error/param")).isEqualTo("email");
    }

    @Test
    void validationErrorsNameTheParameter() {
        Api.Response r = api().post("/dashboard/auth/signup",
                Map.of("name", "Acme", "email", "not-an-email", "password", "short"), Api.none());
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.text("/error/type")).isEqualTo("validation_error");
        assertThat(r.text("/error/code")).isEqualTo("parameter_invalid");
        assertThat(r.text("/error/param")).isIn("email", "password");
    }

    @Test
    void protectedRoutesReturnJsonErrorWithoutToken() {
        Api.Response r = api().get("/dashboard/me", Api.none());
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.headers().getContentType().toString()).startsWith("application/json");
        assertThat(r.text("/error/type")).isEqualTo("authentication_error");
        assertThat(r.headers().getFirst("X-Request-Id")).startsWith("req_");
    }

    @Test
    void tamperedTokenIsRejected() {
        Api api = api();
        String token = api.signupAndGetToken();
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        assertThat(api.get("/dashboard/me", Api.bearer(tampered)).status()).isEqualTo(401);
    }

    @Test
    void demoLoginNeedsNoCredentials() {
        Api.Response r = api().post("/dashboard/auth/demo", null, Api.none());
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.at("/merchant/is_demo").asBoolean()).isTrue();
        assertThat(r.text("/merchant/email")).isEqualTo("demo@paycore.test");
        assertThat(api().get("/dashboard/me", Api.bearer(r.text("/token"))).status()).isEqualTo(200);
    }

    @Test
    void callerSuppliedRequestIdIsHonoured() {
        Api.Response r = api().get("/actuator/health", Map.of("X-Request-Id", "my-trace-123"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.headers().getFirst("X-Request-Id")).isEqualTo("my-trace-123");
    }
}
