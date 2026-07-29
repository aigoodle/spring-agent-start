package io.github.aigoodle.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.aigoodle.common.persistence.BaseEntity;
import io.github.aigoodle.knowledge.enums.IndexingTechnique;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * A knowledge base: a named collection of documents indexed with one embedding
 * model and a fixed processing / retrieval configuration.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dataset")
public class DatasetEntity extends BaseEntity {

    private String name;

    private String description;

    /** Model id (from the model module) used to embed chunks and queries. */
    private String embeddingModelId;

    private IndexingTechnique indexingTechnique;

    /** JSON of {@link io.github.aigoodle.knowledge.config.ProcessRule}. */
    private String processRuleJson;

    /** JSON of {@link io.github.aigoodle.knowledge.config.RetrievalConfig}. */
    private String retrievalConfigJson;

    /** Optional vector store binding name; null = in-memory default. */
    private String vectorStore;

    private Integer documentCount;

    private Integer segmentCount;
}
