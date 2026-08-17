package com.paycore.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM for secrets we must be able to read back (webhook signing secrets). Layout: 12-byte random
 * nonce || ciphertext+tag. GCM gives confidentiality AND integrity, so a tampered row fails to decrypt
 * rather than yielding garbage.
 */
public final class AesGcm {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    public AesGcm(String configuredKey) {
        this.key = new SecretKeySpec(deriveKey(configuredKey), "AES");
    }

    public byte[] encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[NONCE_BYTES + ct.length];
            System.arraycopy(nonce, 0, out, 0, NONCE_BYTES);
            System.arraycopy(ct, 0, out, NONCE_BYTES, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String decrypt(byte[] blob) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, blob, 0, NONCE_BYTES));
            byte[] pt = cipher.doFinal(blob, NONCE_BYTES, blob.length - NONCE_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decryption failed (wrong key or tampered data)", e);
        }
    }

    /** Accepts a base64 32-byte key (recommended) or any string, which is hashed to 32 bytes. */
    static byte[] deriveKey(String configured) {
        try {
            byte[] b = Base64.getDecoder().decode(configured);
            if (b.length == 32) {
                return b;
            }
        } catch (IllegalArgumentException ignored) {
            // not base64
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(configured.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
