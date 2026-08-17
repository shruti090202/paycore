package com.paycore.webhooks;

import com.paycore.common.config.PayCoreProperties;
import com.paycore.common.crypto.AesGcm;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebhooksConfiguration {

    @Bean
    public AesGcm secretCrypto(PayCoreProperties props) {
        return new AesGcm(props.encryptionKey());
    }

    @Bean
    public WebhookUrlPolicy webhookUrlPolicy(WebhookProperties props) {
        return new WebhookUrlPolicy(props.allowPrivateUrls());
    }
}
