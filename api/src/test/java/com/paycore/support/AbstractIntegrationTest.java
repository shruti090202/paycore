package com.paycore.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Boots the whole app on a random port against a real Postgres (Testcontainers).
 * <p>
 * The container is started once per JVM and shared by every IT class: Spring caches the context, so
 * the suite pays the container + Flyway cost once. Tests must therefore create their own merchants
 * rather than assume an empty database.
 * <p>
 * We inject the container's coordinates as the same environment variable names production uses
 * ({@code DATABASE_URL} etc.), so application.yml is exercised exactly as in production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("paycore")
            .withUsername("paycore")
            .withPassword("paycore");

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @LocalServerPort
    protected int port;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("DATABASE_URL", POSTGRES::getJdbcUrl);
        registry.add("FLYWAY_URL", POSTGRES::getJdbcUrl);
        registry.add("DATABASE_USER", POSTGRES::getUsername);
        registry.add("DATABASE_PASSWORD", POSTGRES::getPassword);
        registry.add("REDIS_URL", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    }

    protected Api api() {
        return new Api("http://localhost:" + port);
    }
}
