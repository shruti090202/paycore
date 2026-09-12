package com.paycore.banksim;

import com.paycore.common.config.PayCoreProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;

/** A keyed fingerprint of a card number: stable per card (so velocity and blocklist rules can recognise a card across payments) but useless to an. */
@Component
public class CardFingerprints {

    private final byte[] key;

    public CardFingerprints(PayCoreProperties props) {
        this.key = decodeKey(props.cardFingerprintKey());
    }

    public String fingerprint(String pan) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] out = mac.doFinal(pan.getBytes(StandardCharsets.UTF_8));
            return "fp_" + HexFormat.of().formatHex(out).substring(0, 32);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] decodeKey(String configured) {
        try {
            byte[] b = Base64.getDecoder().decode(configured);
            if (b.length >= 16) {
                return b;
            }
        } catch (IllegalArgumentException ignored) {
            // not base64: fall through and use the raw bytes
        }
        byte[] raw = configured.getBytes(StandardCharsets.UTF_8);
        if (raw.length < 16) {
            throw new IllegalStateException("CARD_FINGERPRINT_KEY must be at least 16 bytes");
        }
        return raw;
    }
}
