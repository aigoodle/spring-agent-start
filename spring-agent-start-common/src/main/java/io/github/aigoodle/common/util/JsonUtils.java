package io.github.aigoodle.common.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.List;
import java.util.Map;

/**
 * Thin Jackson wrapper used across all modules so we never leak a checked
 * {@code JsonProcessingException} into business code.
 */
public final class JsonUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private JsonUtils() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize object to JSON", e);
        }
    }

    public static <T> T parse(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse JSON into " + type.getSimpleName(), e);
        }
    }

    public static <T> T parse(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse JSON", e);
        }
    }

    public static Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return parse(json, new TypeReference<Map<String, Object>>() {
        });
    }

    public static <T> List<T> parseList(String json, Class<T> elementType) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            var listType = MAPPER.getTypeFactory().constructCollectionType(List.class, elementType);
            return MAPPER.readValue(json, listType);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse JSON array", e);
        }
    }

    public static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to read JSON tree", e);
        }
    }

    public static <T> T convert(Object value, Class<T> type) {
        return MAPPER.convertValue(value, type);
    }
}
