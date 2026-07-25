package com.paycore.merchant;

import com.paycore.common.error.PayCoreException;
import com.paycore.common.id.Ids;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * API keys are {@code sk_test_} + 32 base62 chars (~190 bits of entropy) and stored as SHA-256 hashes.
 * <p>
 * Why SHA-256 and not bcrypt: bcrypt exists to slow down guessing of low-entropy passwords. A random
 * 190-bit key cannot be guessed, so slow hashing buys nothing and would cost ~60 ms of CPU on every
 * API call. A fast hash + unique index gives an O(1) lookup and still means a leaked database does
 * not leak usable keys.
 */
@Service
public class ApiKeyService {

    public static final String TEST_PREFIX = "sk_test_";
    private static final int RANDOM_LEN = 32;
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final Duration LAST_USED_WINDOW = Duration.ofMinutes(5);

    private final ApiKeyRepository keys;
    private final JdbcAggregateTemplate template;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public ApiKeyService(ApiKeyRepository keys, JdbcAggregateTemplate template, Clock clock) {
        this.keys = keys;
        this.template = template;
        this.clock = clock;
    }

    /** The plaintext key is returned exactly once, here; it is not recoverable afterwards. */
    public record CreatedKey(ApiKey key, String plaintext) {
    }

    @Transactional
    public CreatedKey create(String merchantId, String name) {
        return createWithPlaintext(merchantId, name, generate());
    }

    /** Used by the demo seeder to create a key whose value is known from configuration. */
    @Transactional
    public CreatedKey createWithPlaintext(String merchantId, String name, String plaintext) {
        if (!looksLikeApiKey(plaintext)) {
            throw PayCoreException.invalid("api_key_format", "API key must start with " + TEST_PREFIX, "api_key");
        }
        ApiKey key = new ApiKey(Ids.newId(Ids.API_KEY), merchantId, name, displayPrefix(plaintext), hash(plaintext),
                clock.instant(), null, null);
        return new CreatedKey(template.insert(key), plaintext);
    }

    @Transactional(readOnly = true)
    public List<ApiKey> list(String merchantId) {
        return keys.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    @Transactional
    public void revoke(String merchantId, String keyId) {
        int updated = keys.revoke(keyId, merchantId, clock.instant());
        if (updated == 0) {
            throw PayCoreException.notFound("api_key", keyId);
        }
    }

    @Transactional(readOnly = true)
    public boolean existsByPlaintext(String plaintext) {
        return keys.findByKeyHash(hash(plaintext)).isPresent();
    }

    /** Resolves a presented key to its merchant. Revoked keys are rejected exactly like unknown ones. */
    @Transactional
    public Optional<ApiKey> authenticate(String plaintext) {
        Optional<ApiKey> found = keys.findByKeyHash(hash(plaintext)).filter(k -> !k.revoked());
        found.ifPresent(k -> {
            Instant now = clock.instant();
            keys.touchIfStale(k.id(), now, now.minus(LAST_USED_WINDOW));
        });
        return found;
    }

    public static boolean looksLikeApiKey(String value) {
        return value != null && value.startsWith(TEST_PREFIX) && value.length() >= TEST_PREFIX.length() + 16;
    }

    static String displayPrefix(String plaintext) {
        return plaintext.substring(0, TEST_PREFIX.length() + 4);
    }

    static String hash(String plaintext) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String generate() {
        StringBuilder sb = new StringBuilder(TEST_PREFIX);
        for (int i = 0; i < RANDOM_LEN; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
