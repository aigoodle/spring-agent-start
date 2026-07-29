package io.github.aigoodle.trigger.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.aigoodle.common.persistence.BaseEntity;
import io.github.aigoodle.trigger.api.TriggerType;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * A persisted trigger: a source ({@link TriggerType}) that fires a target (a workflow,
 * by default) with a mapped payload.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("app_triggers")
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
}
