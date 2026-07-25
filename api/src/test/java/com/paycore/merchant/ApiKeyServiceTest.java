package com.paycore.merchant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyServiceTest {

    @Test
    void recognisesTestKeys() {
        assertThat(ApiKeyService.looksLikeApiKey("sk_test_abcdefghijklmnopqrstuvwxyz012345")).isTrue();
        assertThat(ApiKeyService.looksLikeApiKey("sk_prod_abcdefghijklmnopqrstuvwxyz012345")).as("only test keys exist").isFalse();
        assertThat(ApiKeyService.looksLikeApiKey("pk_test_abcdefghijklmnopqrstuvwxyz012345")).isFalse();
        assertThat(ApiKeyService.looksLikeApiKey("sk_test_short")).isFalse();
        assertThat(ApiKeyService.looksLikeApiKey(null)).isFalse();
        assertThat(ApiKeyService.looksLikeApiKey("eyJhbGciOiJIUzI1NiJ9.jwt.token")).isFalse();
    }

    @Test
    void hashIsDeterministicAndNotThePlaintext() {
        String key = "sk_test_abcdefghijklmnopqrstuvwxyz012345";
        assertThat(ApiKeyService.hash(key)).isEqualTo(ApiKeyService.hash(key)).hasSize(64).doesNotContain("sk_test");
        assertThat(ApiKeyService.hash(key + "x")).isNotEqualTo(ApiKeyService.hash(key));
    }

    @Test
    void displayPrefixRevealsOnlyFourRandomCharacters() {
        assertThat(ApiKeyService.displayPrefix("sk_test_abcdefghijklmnopqrstuvwxyz012345")).isEqualTo("sk_test_abcd");
    }
}
