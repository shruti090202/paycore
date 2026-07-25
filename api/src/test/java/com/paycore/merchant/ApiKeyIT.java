package com.paycore.merchant;

import com.paycore.support.AbstractIntegrationTest;
import com.paycore.support.Api;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyIT extends AbstractIntegrationTest {

    @Test
    void keyIsShownOnceAndListedOnlyByPrefix() {
        Api api = api();
        String token = api.signupAndGetToken();

        Api.Response created = api.post("/dashboard/api_keys", Map.of("name", "backend"), Api.bearer(token));
        assertThat(created.status()).isEqualTo(201);
        String key = created.text("/key");
        assertThat(key).startsWith("sk_test_").hasSize(8 + 32);
        assertThat(created.text("/prefix")).isEqualTo(key.substring(0, 12));

        Api.Response list = api.get("/dashboard/api_keys", Api.bearer(token));
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body()).hasSize(1);
        assertThat(list.raw()).doesNotContain(key).contains(key.substring(0, 12));
        assertThat(list.at("/0/key_hash").isMissingNode()).isTrue();
    }

    @Test
    void apiKeyAuthenticatesMerchantApiAndRevocationTakesEffect() {
        Api api = api();
        String token = api.signupAndGetToken();
        Api.Response created = api.post("/dashboard/api_keys", Map.of("name", "backend"), Api.bearer(token));
        String key = created.text("/key");
        String keyId = created.text("/id");

        Api.Response account = api.get("/v1/account", Api.bearer(key));
        assertThat(account.status()).isEqualTo(200);
        assertThat(account.text("/id")).startsWith("mer_");
        assertThat(account.text("/livemode")).isEqualTo("test");
        assertThat(account.text("/api_key_id")).isEqualTo(keyId);

        assertThat(api.delete("/dashboard/api_keys/" + keyId, Api.bearer(token)).status()).isEqualTo(204);

        Api.Response afterRevoke = api.get("/v1/account", Api.bearer(key));
        assertThat(afterRevoke.status()).isEqualTo(401);
        assertThat(afterRevoke.text("/error/type")).isEqualTo("authentication_error");

        assertThat(api.get("/dashboard/api_keys", Api.bearer(token)).text("/0/revoked_at")).isNotNull();
    }

    @Test
    void credentialsAreNotInterchangeableBetweenAudiences() {
        Api api = api();
        String token = api.signupAndGetToken();
        String key = api.post("/dashboard/api_keys", Map.of("name", "backend"), Api.bearer(token)).text("/key");

        assertThat(api.get("/v1/account", Api.bearer(token)).status()).as("JWT on merchant API").isEqualTo(401);
        assertThat(api.get("/dashboard/me", Api.bearer(key)).status()).as("API key on dashboard").isEqualTo(401);
        assertThat(api.get("/v1/account", Api.none()).status()).isEqualTo(401);
        assertThat(api.get("/v1/account", Api.bearer("sk_test_doesnotexist0000000000000000000")).status()).isEqualTo(401);
    }

    @Test
    void merchantsCannotRevokeEachOthersKeys() {
        Api api = api();
        String tokenA = api.signupAndGetToken();
        String tokenB = api.signupAndGetToken();
        String keyIdA = api.post("/dashboard/api_keys", Map.of("name", "a"), Api.bearer(tokenA)).text("/id");

        Api.Response r = api.delete("/dashboard/api_keys/" + keyIdA, Api.bearer(tokenB));
        assertThat(r.status()).isEqualTo(404);
        assertThat(api.get("/dashboard/api_keys", Api.bearer(tokenA)).text("/0/revoked_at")).isNull();
    }

    @Test
    void demoApiKeyFromConfigurationWorks() {
        Api.Response r = api().get("/v1/account", Api.bearer("sk_test_demo00000000000000000000000000000"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("/name")).isEqualTo("Demo Store");
    }

    @Test
    void internalRoutesRequireTheJobToken() {
        Api api = api();
        Api.Response denied = api.get("/internal/jobs/status", Api.none());
        assertThat(denied.status()).isIn(401, 403);
        assertThat(denied.text("/error/type")).isIn("authentication_error", "permission_error");

        // Correct token -> route exists in a later phase, so 404 is the expected "authenticated" outcome now.
        Api.Response allowed = api.get("/internal/jobs/status", Map.of("X-Internal-Token", "test-internal-token"));
        assertThat(allowed.status()).isEqualTo(404);
        assertThat(allowed.text("/error/code")).isEqualTo("route_missing");
    }

    @Test
    void publicOpsEndpointsAreOpen() {
        Api api = api();
        assertThat(api.get("/actuator/health", Api.none()).status()).isEqualTo(200);
        assertThat(api.get("/actuator/health/liveness", Api.none()).status()).isEqualTo(200);
        assertThat(api.get("/v3/api-docs", Api.none()).status()).isEqualTo(200);
        assertThat(api.get("/v3/api-docs", Api.none()).raw()).contains("/v1/account").doesNotContain("/internal/");
    }
}
