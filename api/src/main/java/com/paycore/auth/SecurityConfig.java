package com.paycore.auth;

import com.paycore.common.config.PayCoreProperties;
import com.paycore.merchant.ApiKeyService;
import jakarta.servlet.Filter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Three stateless filter chains, one per audience: /v1/** merchant API, API key (sk_test_...) /dashboard/** merchant dashboard, JWT (except. */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** bcrypt cost 10: ~60 ms on a laptop, tolerable on Render's 0.1 vCPU. Cost 12 would take seconds there. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    @Order(1)
    public SecurityFilterChain merchantApiChain(HttpSecurity http, ApiKeyService apiKeyService, ObjectMapper mapper,
                                                List<MerchantApiFilter> apiFilters) throws Exception {
        JsonAuthErrorHandlers handlers = new JsonAuthErrorHandlers(mapper);
        http.securityMatcher("/v1/**")
                .csrf(c -> c.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ApiKeyAuthenticationFilter(apiKeyService), BasicAuthenticationFilter.class);
        // Contributed filters (rate limiting, idempotency, ...) run after authentication, in order.
        Class<? extends Filter> previous = ApiKeyAuthenticationFilter.class;
        for (MerchantApiFilter f : apiFilters.stream().sorted(Comparator.comparingInt(MerchantApiFilter::order)).toList()) {
            http.addFilterAfter(f.filter(), previous);
            previous = f.filter().getClass();
        }
        http
                .authorizeHttpRequests(a -> a.anyRequest().hasAuthority(MerchantAuthentication.ROLE_API))
                .exceptionHandling(e -> e.authenticationEntryPoint(handlers.entryPoint())
                        .accessDeniedHandler(handlers.accessDenied()));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain dashboardChain(HttpSecurity http, JwtService jwtService, ObjectMapper mapper)
            throws Exception {
        JsonAuthErrorHandlers handlers = new JsonAuthErrorHandlers(mapper);
        http.securityMatcher("/dashboard/**")
                .csrf(c -> c.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/dashboard/auth/**").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().hasAuthority(MerchantAuthentication.ROLE_DASHBOARD))
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.decoder(jwtService.decoder()).jwtAuthenticationConverter(SecurityConfig::toMerchantAuth))
                        .authenticationEntryPoint(handlers.entryPoint())
                        .accessDeniedHandler(handlers.accessDenied()))
                .exceptionHandling(e -> e.authenticationEntryPoint(handlers.entryPoint())
                        .accessDeniedHandler(handlers.accessDenied()));
        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain publicAndInternalChain(HttpSecurity http, PayCoreProperties props, ObjectMapper mapper)
            throws Exception {
        JsonAuthErrorHandlers handlers = new JsonAuthErrorHandlers(mapper);
        http.csrf(c -> c.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new InternalTokenFilter(props.internalJobToken()), BasicAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/internal/**").hasAuthority(InternalTokenFilter.ROLE_INTERNAL)
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/v3/api-docs/**",
                                "/swagger-ui/**", "/swagger-ui.html", "/checkout/**", "/error").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(handlers.entryPoint())
                        .accessDeniedHandler(handlers.accessDenied()));
        return http.build();
    }

    private static MerchantAuthentication toMerchantAuth(Jwt jwt) {
        String scope = jwt.getClaimAsString(JwtService.SCOPE_CLAIM);
        if (!JwtService.SCOPE_DASHBOARD.equals(scope)) {
            // A token with a foreign scope authenticates as nothing useful: no ROLE_DASHBOARD -> 403.
            return new MerchantAuthentication(new MerchantPrincipal(jwt.getSubject(), null), "ROLE_NONE");
        }
        return new MerchantAuthentication(new MerchantPrincipal(jwt.getSubject(), null), MerchantAuthentication.ROLE_DASHBOARD);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(PayCoreProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(Arrays.stream(props.corsAllowedOrigins().split(",")).map(String::trim).toList());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Request-Id"));
        cfg.setExposedHeaders(List.of("X-Request-Id", "Retry-After", "X-RateLimit-Limit", "X-RateLimit-Remaining", "X-RateLimit-Reset"));
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
