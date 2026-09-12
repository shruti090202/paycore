package com.paycore.merchant;

import com.paycore.common.config.PayCoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;

/** Creates the demo merchant (and its API key) on startup if missing, so "Try the demo" works on a fresh database. */
@Component
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final MerchantService merchantService;
    private final MerchantRepository merchants;
    private final ApiKeyService apiKeyService;
    private final PayCoreProperties props;

    public DemoDataSeeder(MerchantService merchantService, MerchantRepository merchants,
                          ApiKeyService apiKeyService, PayCoreProperties props) {
        this.merchantService = merchantService;
        this.merchants = merchants;
        this.apiKeyService = apiKeyService;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        PayCoreProperties.Demo demo = props.demo();
        if (demo == null || demo.email() == null || demo.email().isBlank()) {
            log.info("Demo merchant not configured; skipping seed");
            return;
        }
        Merchant merchant = merchants.findByEmail(demo.email().toLowerCase()).orElseGet(() -> {
            String password = (demo.password() == null || demo.password().isBlank()) ? randomSecret() : demo.password();
            Merchant m = merchantService.create("Demo Store", demo.email(), password, true);
            log.info("Seeded demo merchant {} ({})", m.id(), m.email());
            return m;
        });

        String configuredKey = demo.apiKey();
        if (configuredKey != null && !configuredKey.isBlank()) {
            if (!apiKeyService.existsByPlaintext(configuredKey)) {
                apiKeyService.createWithPlaintext(merchant.id(), "Demo store (configured)", configuredKey);
                log.info("Seeded configured demo API key for merchant {}", merchant.id());
            }
        } else if (apiKeyService.list(merchant.id()).isEmpty()) {
            ApiKeyService.CreatedKey created = apiKeyService.create(merchant.id(), "Demo store");
            // Printed once on purpose: without DEMO_API_KEY there is no other way to learn it.
            log.warn("Generated demo API key (shown once, set DEMO_API_KEY to make it stable): {}", created.plaintext());
        }
    }

    private static String randomSecret() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
