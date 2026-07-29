package io.github.aigoodle.model.service;

import io.github.aigoodle.model.enums.ModelType;
import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * Input for registering or updating a model.
 */
@Data
@Builder
public class ModelRegistration {

    private String tenantId;
    private String providerName;
    private String modelName;
    private ModelType modelType;

    /** Optional reference to a shared provider credential row. */
    private String credentialId;

    /** Inline model-level credentials/overrides (apiKey, baseUrl, dimensions, ...). */
    @Builder.Default
    private Map<String, Object> credentials = new HashMap<>();

    private boolean asDefault;
}
