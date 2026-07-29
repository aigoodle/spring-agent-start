package io.github.aigoodle.model.provider;

import io.github.aigoodle.model.enums.ModelType;
import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * A fully-resolved, decrypted model configuration handed to a {@link ModelProvider}
 * so it can build the concrete Spring AI model object.
 * <p>
 * This is intentionally a flat value object: persistence, encryption and tenant
 * concerns are resolved <em>before</em> a provider ever sees it.
 */
@Data
@Builder
public class ModelEndpoint {

    /** Stable identifier of the backing persisted model row (used as cache key). */
    private String id;

    private String providerName;

    private String modelName;

    private ModelType modelType;

    /** OpenAI-compatible base url; null lets the provider use its default. */
    private String baseUrl;

    private String apiKey;

    /** Provider-specific extras (e.g. {@code dimensions}, {@code endpointId}, {@code organization}). */
    @Builder.Default
    private Map<String, Object> properties = new HashMap<>();

    public String property(String key) {
        Object v = properties == null ? null : properties.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public String propertyOrDefault(String key, String defaultValue) {
        String v = property(key);
        return v == null || v.isBlank() ? defaultValue : v;
    }

    public Integer intProperty(String key) {
        String v = property(key);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
