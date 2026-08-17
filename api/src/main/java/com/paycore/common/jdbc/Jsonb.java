package com.paycore.common.jdbc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * A JSON document destined for a Postgres {@code jsonb} column. Spring Data JDBC maps it via
 * {@link JdbcConfig}'s converters; entities hold this type instead of raw strings so the mapping is explicit.
 */
public record Jsonb(String json) {

    /** Same conventions as the HTTP API (snake_case, no nulls, ISO instants) so stored JSON == what clients see. */
    private static final ObjectMapper MAPPER = tools.jackson.databind.json.JsonMapper.builder()
            .propertyNamingStrategy(tools.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL))
            .build();
    public static final Jsonb EMPTY_OBJECT = new Jsonb("{}");

    public static Jsonb of(Object value) {
        return value == null ? EMPTY_OBJECT : new Jsonb(MAPPER.writeValueAsString(value));
    }

    public JsonNode node() {
        return MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> asMap() {
        return MAPPER.readValue(json == null || json.isBlank() ? "{}" : json, Map.class);
    }
}
