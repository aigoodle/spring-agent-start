package io.github.aigoodle.trigger.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.aigoodle.common.persistence.BaseEntity;
import io.github.aigoodle.trigger.api.TriggerType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * A persisted trigger: a source ({@link TriggerType}) that fires a target (a workflow,
 * by default) with a mapped payload.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("goodle_app_triggers")
public class TriggerEntity extends BaseEntity {

    private String name;

    private TriggerType type;

    private Boolean enabled;

    /** Dispatch target type, resolved by a {@code TriggerDispatcher} (e.g. {@code workflow}). */
    private String targetType;

    private String targetId;

    /**
     * Type-specific config JSON: webhook {@code {path,token}}, cron {@code {expression}},
     * event {@code {eventName}}.
     */
    private String configJson;

    /** Persisted scheduling cursor used by every application node. */
    private LocalDateTime nextFireAt;

    private LocalDateTime lastFireAt;

    /** Short database lease that guarantees one cluster node owns a due firing. */
    private LocalDateTime lockUntil;

    private String lockOwner;

    private Long fireCount;
}
