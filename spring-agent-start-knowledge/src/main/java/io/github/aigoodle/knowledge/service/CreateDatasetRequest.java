package io.github.aigoodle.knowledge.service;

import io.github.aigoodle.knowledge.config.ProcessRule;
import io.github.aigoodle.knowledge.config.RetrievalConfig;
import io.github.aigoodle.knowledge.enums.IndexingTechnique;
import lombok.Builder;
import lombok.Data;

/**
 * Input for creating a dataset.
 */
@Data
@Builder
public class CreateDatasetRequest {

    private String tenantId;
    private String name;
    private String description;
    private String embeddingModelId;
    @Builder.Default
    private IndexingTechnique indexingTechnique = IndexingTechnique.HIGH_QUALITY;
    private ProcessRule processRule;
    private RetrievalConfig retrievalConfig;
    private String vectorStore;
}
