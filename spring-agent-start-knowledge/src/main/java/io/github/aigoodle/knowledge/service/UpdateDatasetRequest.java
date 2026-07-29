package io.github.aigoodle.knowledge.service;

import io.github.aigoodle.knowledge.config.ProcessRule;
import io.github.aigoodle.knowledge.config.RetrievalConfig;
import io.github.aigoodle.knowledge.enums.IndexingTechnique;
import lombok.Data;

/**
 * Patch body for {@link DatasetService#update(String, UpdateDatasetRequest)}. All
 * fields are optional — omitted fields keep their current value in the DB.
 */
@Data
public class UpdateDatasetRequest {

    private String name;
    private String description;
    private String embeddingModelId;
    private IndexingTechnique indexingTechnique;
    private ProcessRule processRule;
    private RetrievalConfig retrievalConfig;
    private String vectorStore;
}
