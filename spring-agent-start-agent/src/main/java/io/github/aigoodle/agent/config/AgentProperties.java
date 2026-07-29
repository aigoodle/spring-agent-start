package io.github.aigoodle.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent runtime configuration ({@code spring-agent.agent.*}).
 */
@ConfigurationProperties(prefix = "spring-agent.agent")
public class AgentProperties {

    /** Memory backend: {@code jdbc} (recent, persisted) or {@code vector} (semantic recall). */
    private String memory = "jdbc";

    /** Embedding model id required when {@code memory=vector}. */
    private String embeddingModelId;

    /** Dataset name used by vector memory. */
    private String memoryDatasetName = "agent-memory";

    public String getMemory() {
        return memory;
    }

    public void setMemory(String memory) {
        this.memory = memory;
    }

    public String getEmbeddingModelId() {
        return embeddingModelId;
    }

    public void setEmbeddingModelId(String embeddingModelId) {
        this.embeddingModelId = embeddingModelId;
    }

    public String getMemoryDatasetName() {
        return memoryDatasetName;
    }

    public void setMemoryDatasetName(String memoryDatasetName) {
        this.memoryDatasetName = memoryDatasetName;
    }
}
