package io.github.aigoodle.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.aigoodle.common.persistence.BaseEntity;
import io.github.aigoodle.knowledge.enums.IndexVersionStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Immutable indexing configuration plus its blue/green lifecycle state. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("goodle_index_versions")
public class IndexVersionEntity extends BaseEntity {
    private String datasetId;
    private String version;
    private String embeddingModelVersion;
    private String chunkingRuleVersion;
    private String contentChecksum;
    private IndexVersionStatus status;
    private String errorMessage;
    private Integer documentCount;
    private Integer segmentCount;
}
