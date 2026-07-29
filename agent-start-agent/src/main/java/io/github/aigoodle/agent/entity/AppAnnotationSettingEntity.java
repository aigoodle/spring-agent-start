package io.github.aigoodle.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.aigoodle.common.persistence.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Per-app annotation retrieval configuration (Dify parity — the "标注设置" panel).
 * The {@link AppAnnotationEntity} rows hold the QA pairs; this row picks the
 * embedding model + score threshold used to decide whether an incoming query
 * matches an annotation closely enough to serve the canned answer.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("app_annotation_settings")
public class AppAnnotationSettingEntity extends BaseEntity {

    /** FK to {@code apps.id}. */
    private String appId;

    /** Minimum similarity score to serve the canned answer. Range 0.0 – 1.0. */
    private Float scoreThreshold;

    /** Embedding model used to compare a query with annotation questions. */
    private String embeddingModelId;

    /** Global switch for the whole annotation flow on this app. */
    private Boolean enabled;
}
