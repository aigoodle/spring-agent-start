package io.github.aigoodle.trigger.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.aigoodle.common.persistence.BaseEntity;
import io.github.aigoodle.trigger.api.InvocationStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * One firing of a trigger — kept for history and replay.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trigger_invocations")
public class TriggerInvocationEntity extends BaseEntity {

    private String triggerId;

    /** {@code webhook}, {@code cron}, {@code event}, {@code manual}, {@code replay}. */
    private String source;

    private InvocationStatus status;

    /** The trigger payload, stored so the invocation can be replayed exactly. */
    private String payloadJson;

    /** Id of the produced workflow run (or other target run). */
    private String runId;

    private String outputsJson;

    private String error;

    /** When this is a replay, the id of the original invocation. */
    private String replayOf;
}
