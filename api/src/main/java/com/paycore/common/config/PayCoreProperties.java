package com.paycore.common.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** All application settings come from environment variables (see .env.example); nothing secret lives in the repo. */
@Validated
@ConfigurationProperties(prefix = "paycore")
public record PayCoreProperties(
        Jwt jwt,
        @NotBlank String encryptionKey,
        @NotBlank String cardFingerprintKey,
        @NotBlank String internalJobToken,
        @NotBlank String checkoutBaseUrl,
        @NotBlank String corsAllowedOrigins,
        Demo demo
) {
    public record Jwt(@NotBlank String secret, int ttlMinutes) {
    }

    public record Demo(String email, String password, String apiKey) {
    }
}
