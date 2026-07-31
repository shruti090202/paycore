package com.paycore.common.jdbc;

import org.postgresql.util.PGobject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions;

import java.sql.SQLException;
import java.util.List;

/** Registers {@link Jsonb} <-> {@code jsonb} conversions with Spring Data JDBC. */
@Configuration
public class JdbcConfig {

    @Bean
    public JdbcCustomConversions jdbcCustomConversions() {
        return new JdbcCustomConversions(List.of(new JsonbWriter(), new JsonbReader()));
    }

    @WritingConverter
    static class JsonbWriter implements Converter<Jsonb, PGobject> {
        @Override
        public PGobject convert(Jsonb source) {
            PGobject pg = new PGobject();
            pg.setType("jsonb");
            try {
                pg.setValue(source.json());
            } catch (SQLException e) {
                throw new IllegalArgumentException(e);
            }
            return pg;
        }
    }

    @ReadingConverter
    static class JsonbReader implements Converter<PGobject, Jsonb> {
        @Override
        public Jsonb convert(PGobject source) {
            return new Jsonb(source.getValue());
        }
    }
}
