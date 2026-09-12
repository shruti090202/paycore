package com.paycore.common.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Lets DATABASE_URL (and optionally DATABASE_DIRECT_URL for Flyway) be a plain Postgres URI as hosting providers hand it out —. */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        Map<String, Object> props = new HashMap<>();
        Parsed app = parse(env.getProperty("DATABASE_URL"));
        if (app != null) {
            props.put("spring.datasource.url", app.jdbcUrl());
            props.put("spring.datasource.username", app.user());
            props.put("spring.datasource.password", app.password());
        }
        String directRaw = env.getProperty("DATABASE_DIRECT_URL");
        Parsed direct = parse(directRaw != null ? directRaw : env.getProperty("FLYWAY_URL"));
        Parsed flyway = direct != null ? direct : app;
        if (flyway != null) {
            props.put("spring.flyway.url", flyway.jdbcUrl());
            props.put("spring.flyway.user", flyway.user());
            props.put("spring.flyway.password", flyway.password());
        }
        if (!props.isEmpty()) {
            env.getPropertySources().addFirst(new MapPropertySource("databaseUrl", props));
        }
    }

    record Parsed(String jdbcUrl, String user, String password) {
    }

    /** Returns null for blank input or anything that is not a postgres:// / postgresql:// URI. */
    static Parsed parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        if (!(s.startsWith("postgres://") || s.startsWith("postgresql://"))) {
            return null;
        }
        URI uri = URI.create(s.replaceFirst("^postgres://", "postgresql://"));
        String user = null;
        String password = null;
        if (uri.getUserInfo() != null) {
            String[] parts = uri.getUserInfo().split(":", 2);
            user = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            password = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : null;
        }
        String host = uri.getHost();
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String path = uri.getPath() == null || uri.getPath().isBlank() ? "/postgres" : uri.getPath();
        String query = uri.getRawQuery();
        String jdbc = "jdbc:postgresql://" + host + ":" + port + path + (query == null ? "" : "?" + query);
        return new Parsed(jdbc, user, password);
    }
}
