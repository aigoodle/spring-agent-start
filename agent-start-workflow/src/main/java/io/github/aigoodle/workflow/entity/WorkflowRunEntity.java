package io.github.aigoodle.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.aigoodle.common.persistence.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * One execution of a workflow, kept for observability / replay.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_runs")
public class WorkflowRunEntity extends BaseEntity {

    private String workflowId;

    private String conversationId;

    /** {@code SUCCESS} or {@code FAILED}. */
    private String status;

    private String inputsJson;

    private String outputsJson;

    private String stepsJson;

    private String error;

    private Long elapsedMillis;
}
