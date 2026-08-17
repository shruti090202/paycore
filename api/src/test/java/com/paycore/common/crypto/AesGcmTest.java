package com.paycore.common.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmTest {

    @Test
    void roundTripsAndRandomizesNonce() {
        AesGcm crypto = new AesGcm(Base64.getEncoder().encodeToString(new byte[32]));
        byte[] a = crypto.encrypt("whsec_abc");
        byte[] b = crypto.encrypt("whsec_abc");
        assertThat(crypto.decrypt(a)).isEqualTo("whsec_abc");
        assertThat(a).as("fresh nonce every time: identical plaintexts encrypt differently").isNotEqualTo(b);
        assertThat(new String(a)).doesNotContain("whsec");
    }

    @Test
    void tamperingOrWrongKeyFailsLoudly() {
        AesGcm crypto = new AesGcm("any-string-is-hashed-to-a-key");
        byte[] blob = crypto.encrypt("secret");
        blob[blob.length - 1] ^= 0x01;
        assertThatThrownBy(() -> crypto.decrypt(blob)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AesGcm("another-key").decrypt(crypto.encrypt("secret"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsBase64KeyOrDerivesOne() {
        assertThat(AesGcm.deriveKey(Base64.getEncoder().encodeToString(new byte[32]))).hasSize(32);
        assertThat(AesGcm.deriveKey("short")).hasSize(32);
    }
}
