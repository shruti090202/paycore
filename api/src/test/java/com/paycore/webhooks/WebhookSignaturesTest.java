package com.paycore.webhooks;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignaturesTest {

    private static final String SECRET = "whsec_testsecret000000000000000000000000";
    private static final String BODY = "{\"id\":\"evt_1\",\"type\":\"payment.captured\"}";

    @Test
    void signsAndVerifiesWithinTolerance() {
        long t = 1_800_000_000L;
        String header = WebhookSignatures.header(SECRET, t, BODY);
        assertThat(header).startsWith("t=" + t + ",v1=").hasSize("t=1800000000,v1=".length() + 64);
        assertThat(WebhookSignatures.verify(SECRET, header, BODY, t + 100, 300)).isTrue();
        assertThat(WebhookSignatures.verify(SECRET, header, BODY, t - 100, 300)).isTrue();
    }

    @Test
    void rejectsStaleTamperedOrWrongSecret() {
        long t = 1_800_000_000L;
        String header = WebhookSignatures.header(SECRET, t, BODY);
        assertThat(WebhookSignatures.verify(SECRET, header, BODY, t + 301, 300)).as("replayed too late").isFalse();
        assertThat(WebhookSignatures.verify(SECRET, header, BODY + " ", t, 300)).as("body changed by one byte").isFalse();
        assertThat(WebhookSignatures.verify("whsec_other", header, BODY, t, 300)).as("wrong secret").isFalse();
        assertThat(WebhookSignatures.verify(SECRET, "t=" + (t + 1) + header.substring(header.indexOf(",")), BODY, t, 300))
                .as("timestamp changed without re-signing").isFalse();
        assertThat(WebhookSignatures.verify(SECRET, null, BODY, t, 300)).isFalse();
        assertThat(WebhookSignatures.verify(SECRET, "garbage", BODY, t, 300)).isFalse();
        assertThat(WebhookSignatures.verify(SECRET, "t=abc,v1=00", BODY, t, 300)).isFalse();
    }

    @Test
    void signatureIsDeterministicForTheSameInputs() {
        assertThat(WebhookSignatures.header(SECRET, 1, BODY)).isEqualTo(WebhookSignatures.header(SECRET, 1, BODY));
        assertThat(WebhookSignatures.header(SECRET, 1, BODY)).isNotEqualTo(WebhookSignatures.header(SECRET, 2, BODY));
    }
}
