package com.paycore.support;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

/**
 * Tiny HTTP helper for integration tests: plain {@link RestClient}, never throws on 4xx/5xx,
 * returns parsed JSON so assertions read like the API docs.
 */
public class Api {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient client;

    public Api(String baseUrl) {
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultStatusHandler(s -> true, (req, res) -> { /* never throw; tests assert on status */ })
                .build();
    }

    public record Response(int status, HttpHeaders headers, JsonNode body, String raw) {
        public JsonNode at(String pointer) {
            return body.at(pointer);
        }

        public String text(String pointer) {
            JsonNode n = body.at(pointer);
            return n.isMissingNode() || n.isNull() ? null : n.asString();
        }
    }

    public Response get(String path, Map<String, String> headers) {
        ResponseEntity<String> re = client.get().uri(path).headers(h -> h.setAll(headers)).retrieve().toEntity(String.class);
        return wrap(re);
    }

    public Response post(String path, Object body, Map<String, String> headers) {
        ResponseEntity<String> re = client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                .headers(h -> h.setAll(headers)).body(body == null ? "" : toJson(body)).retrieve().toEntity(String.class);
        return wrap(re);
    }

    public Response delete(String path, Map<String, String> headers) {
        ResponseEntity<String> re = client.delete().uri(path).headers(h -> h.setAll(headers)).retrieve().toEntity(String.class);
        return wrap(re);
    }

    public static Map<String, String> bearer(String token) {
        return Map.of("Authorization", "Bearer " + token);
    }

    public static Map<String, String> none() {
        return Map.of();
    }

    public static String uniqueEmail() {
        return "m-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }

    /** Signs up a fresh merchant and returns its dashboard token. */
    public String signupAndGetToken() {
        Response r = post("/dashboard/auth/signup",
                Map.of("name", "Test Merchant", "email", uniqueEmail(), "password", "correct-horse-battery"), none());
        if (r.status() != 201) {
            throw new IllegalStateException("signup failed: " + r.raw());
        }
        return r.text("/token");
    }

    /** Signs up a fresh merchant, creates an API key and returns the plaintext key. */
    public String signupAndGetApiKey() {
        String token = signupAndGetToken();
        Response r = post("/dashboard/api_keys", Map.of("name", "test"), bearer(token));
        if (r.status() != 201) {
            throw new IllegalStateException("key creation failed: " + r.raw());
        }
        return r.text("/key");
    }

    private static String toJson(Object body) {
        return body instanceof String s ? s : JSON.writeValueAsString(body);
    }

    private static Response wrap(ResponseEntity<String> re) {
        String raw = re.getBody() == null ? "" : re.getBody();
        JsonNode node;
        try {
            node = raw.isBlank() ? JSON.nullNode() : JSON.readTree(raw);
        } catch (RuntimeException e) {
            node = JSON.nullNode();
        }
        return new Response(re.getStatusCode().value(), re.getHeaders(), node, raw);
    }
}
