package com.paycore.webhooks;

import com.paycore.common.error.PayCoreException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookUrlPolicyTest {

    private final WebhookUrlPolicy prod = new WebhookUrlPolicy(false);
    private final WebhookUrlPolicy dev = new WebhookUrlPolicy(true);

    private static String code(Throwable t) {
        return ((PayCoreException) t).code();
    }

    @Test
    void productionRejectsSsrfTargets() {
        assertThatThrownBy(() -> prod.validate("http://example.com/hook")).satisfies(t -> org.assertj.core.api.Assertions.assertThat(code(t)).isEqualTo("url_scheme"));
        assertThatThrownBy(() -> prod.validate("https://localhost/hook")).satisfies(t -> org.assertj.core.api.Assertions.assertThat(code(t)).isEqualTo("url_private"));
        assertThatThrownBy(() -> prod.validate("https://127.0.0.1/hook")).satisfies(t -> org.assertj.core.api.Assertions.assertThat(code(t)).isEqualTo("url_private"));
        assertThatThrownBy(() -> prod.validate("https://10.0.0.5/hook")).satisfies(t -> org.assertj.core.api.Assertions.assertThat(code(t)).isEqualTo("url_private"));
        assertThatThrownBy(() -> prod.validate("https://169.254.169.254/latest/meta-data")).as("cloud metadata")
                .satisfies(t -> org.assertj.core.api.Assertions.assertThat(code(t)).isEqualTo("url_private"));
        assertThatThrownBy(() -> prod.validate("https://user:pw@example.com/hook")).satisfies(t -> org.assertj.core.api.Assertions.assertThat(code(t)).isEqualTo("url_invalid"));
        assertThatThrownBy(() -> prod.validate("not a url")).isInstanceOf(PayCoreException.class);
    }

    @Test
    void devAllowsLocalHttp() {
        assertThatCode(() -> dev.validate("http://localhost:5555/hook")).doesNotThrowAnyException();
        assertThatCode(() -> dev.validate("http://127.0.0.1:5555/hook")).doesNotThrowAnyException();
        assertThatThrownBy(() -> dev.validate("ftp://example.com/x")).satisfies(t -> org.assertj.core.api.Assertions.assertThat(code(t)).isEqualTo("url_scheme"));
    }
}
