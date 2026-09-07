package com.paycore.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseUrlEnvironmentPostProcessorTest {

    @Test
    void parsesNeonStyleUris() {
        var p = DatabaseUrlEnvironmentPostProcessor.parse("postgresql://neondb_owner:p%40ss@ep-cool-123-pooler.eu-central-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require");
        assertThat(p.jdbcUrl()).isEqualTo("jdbc:postgresql://ep-cool-123-pooler.eu-central-1.aws.neon.tech:5432/neondb?sslmode=require&channel_binding=require");
        assertThat(p.user()).isEqualTo("neondb_owner");
        assertThat(p.password()).isEqualTo("p@ss");

        var short_ = DatabaseUrlEnvironmentPostProcessor.parse("postgres://u:p@localhost:5433/db");
        assertThat(short_.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5433/db");
    }

    @Test
    void leavesJdbcUrlsAlone() {
        assertThat(DatabaseUrlEnvironmentPostProcessor.parse("jdbc:postgresql://localhost:5432/paycore")).isNull();
        assertThat(DatabaseUrlEnvironmentPostProcessor.parse("")).isNull();
        assertThat(DatabaseUrlEnvironmentPostProcessor.parse(null)).isNull();
    }

    @Test
    void pooledForTheAppDirectForFlyway() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("DATABASE_URL", "postgresql://u:p@ep-x-pooler.neon.tech/db?sslmode=require")
                .withProperty("DATABASE_DIRECT_URL", "postgresql://u:p@ep-x.neon.tech/db?sslmode=require");
        new DatabaseUrlEnvironmentPostProcessor().postProcessEnvironment(env, null);
        assertThat(env.getProperty("spring.datasource.url")).contains("ep-x-pooler.neon.tech");
        assertThat(env.getProperty("spring.flyway.url")).contains("ep-x.neon.tech:5432").doesNotContain("pooler");
        assertThat(env.getProperty("spring.datasource.username")).isEqualTo("u");
        assertThat(env.getProperty("spring.flyway.password")).isEqualTo("p");
    }

    @Test
    void withoutADirectUrlFlywayUsesTheAppUrl() {
        MockEnvironment env = new MockEnvironment().withProperty("DATABASE_URL", "postgresql://u:p@host/db");
        new DatabaseUrlEnvironmentPostProcessor().postProcessEnvironment(env, null);
        assertThat(env.getProperty("spring.flyway.url")).isEqualTo("jdbc:postgresql://host:5432/db");
    }

    @Test
    void jdbcStyleConfigurationIsUntouched() {
        MockEnvironment env = new MockEnvironment().withProperty("DATABASE_URL", "jdbc:postgresql://localhost/paycore");
        new DatabaseUrlEnvironmentPostProcessor().postProcessEnvironment(env, null);
        assertThat(env.getProperty("spring.datasource.url")).isNull();
    }
}
