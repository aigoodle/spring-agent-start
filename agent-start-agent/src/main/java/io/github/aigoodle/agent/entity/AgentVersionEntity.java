package io.github.aigoodle.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.aigoodle.common.persistence.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** Immutable runtime snapshot created whenever an Agent application is published. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("goodle_agent_versions")
public class AgentVersionEntity extends BaseEntity {
    private String appId;
    private Integer versionNumber;
    /** ACTIVE / SUPERSEDED / DISABLED. */
    private String status;
    /** Serialized {@code AgentDefinition}; runtime never rereads the mutable draft. */
    private String definitionJson;
    private String changeSummary;
    private String publishedBy;
    private LocalDateTime publishedAt;
    private String rollbackFromVersionId;
}
