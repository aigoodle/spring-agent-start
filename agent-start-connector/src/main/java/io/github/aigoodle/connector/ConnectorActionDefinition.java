package io.github.aigoodle.connector;

import java.time.Duration;
import java.util.Map;

/** One callable operation contributed by a connector. */
public record ConnectorActionDefinition(
        String id,
        String name,
        String description,
        String inputSchema,
        String outputSchema,
        boolean idempotent,
        Duration timeout,
        ConnectorRiskLevel riskLevel,
        Map<String, Object> metadata) {

    public ConnectorActionDefinition {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("action id must not be blank");
        name = name == null || name.isBlank() ? id : name;
        description = description == null ? name : description;
        inputSchema = inputSchema == null || inputSchema.isBlank()
                ? "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":true}" : inputSchema;
        riskLevel = riskLevel == null ? ConnectorRiskLevel.WRITE : riskLevel;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
