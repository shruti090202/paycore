package com.paycore.webhooks;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Webhook signing, Stripe-style: {@code PayCore-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256>} where the
 * signed payload is {@code "<t>.<raw body>"}. Including the timestamp in the MAC means a captured request
 * cannot be replayed later (verifiers reject stale timestamps), and signing the raw bytes means any
 * re-serialization by a proxy breaks verification — which is the point.
 */
public final class WebhookSignatures {

    public static final String HEADER = "PayCore-Signature";
    public static final long DEFAULT_TOLERANCE_SECONDS = 300;

    private WebhookSignatures() {
    }

    public static String header(String secret, long timestampSeconds, String body) {
        return "t=" + timestampSeconds + ",v1=" + hmac(secret, timestampSeconds + "." + body);
    }

    /** Verifier used by tests (and mirrored in the demo store's TypeScript). */
    public static boolean verify(String secret, String header, String body, long nowSeconds, long toleranceSeconds) {
        if (header == null) {
            return false;
        }
        long t = -1;
        String v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                try {
                    t = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (kv[0].equals("v1")) {
                v1 = kv[1];
            }
        }
        if (t < 0 || v1 == null) {
            return false;
        }
        if (Math.abs(nowSeconds - t) > toleranceSeconds) {
            return false;
        }
        String expected = hmac(secret, t + "." + body);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), v1.getBytes(StandardCharsets.UTF_8));
    }

    static String hmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
