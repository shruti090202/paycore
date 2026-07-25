package com.paycore.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String MERCHANT_API_KEY = "merchantApiKey";
    public static final String DASHBOARD_JWT = "dashboardJwt";

    @Bean
    public OpenAPI payCoreOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("PayCore API")
                        .version("v1")
                        .description("""
                                Simulated payment gateway for education/portfolio use.
                                **Never send real card numbers** - only the documented test cards are accepted.

                                * `/v1/**` - merchant API, authenticate with `Authorization: Bearer sk_test_...`
                                * `/checkout/**` - hosted checkout, authenticated by the session token in the URL
                                * `/dashboard/**` - merchant dashboard, authenticate with a JWT from `/dashboard/auth/login`
                                """))
                .components(new Components()
                        .addSecuritySchemes(MERCHANT_API_KEY, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer")
                                .description("Merchant secret key, e.g. sk_test_..."))
                        .addSecuritySchemes(DASHBOARD_JWT, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}
