package com.paycore.idempotency;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyFilterTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void requestHashCoversMethodPathAndBody() {
        String base = IdempotencyFilter.hash("POST", "/v1/payments", b("{\"amount_minor\":100}"));
        assertThat(base).hasSize(64);
        assertThat(IdempotencyFilter.hash("POST", "/v1/payments", b("{\"amount_minor\":100}"))).isEqualTo(base);
        assertThat(IdempotencyFilter.hash("POST", "/v1/payments", b("{\"amount_minor\":101}"))).isNotEqualTo(base);
        assertThat(IdempotencyFilter.hash("PUT", "/v1/payments", b("{\"amount_minor\":100}"))).isNotEqualTo(base);
        assertThat(IdempotencyFilter.hash("POST", "/v1/refunds", b("{\"amount_minor\":100}"))).isNotEqualTo(base);
        // Separator matters: "POST" + "/a" + "b" must differ from "POST" + "/ab" + "".
        assertThat(IdempotencyFilter.hash("POST", "/a", b("b"))).isNotEqualTo(IdempotencyFilter.hash("POST", "/ab", b("")));
    }
}
