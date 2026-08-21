package io.github.aigoodle.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.common.context.UserContextHolder;
import io.github.aigoodle.workflow.engine.WorkflowRunResult;
import io.github.aigoodle.workflow.entity.WorkflowRunEntity;
import io.github.aigoodle.workflow.mapper.WorkflowRunMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/** Isolates the best-effort observability persistence from workflow execution. */
final class WorkflowRunStore {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunStore.class);
    private static final int MAX_HISTORY_SIZE = 200;
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";

    private final WorkflowRunMapper workflowRunMapper;

    WorkflowRunStore(WorkflowRunMapper workflowRunMapper) {
        this.workflowRunMapper = workflowRunMapper;
    }

    List<WorkflowRunEntity> findRecent(String workflowId, int requestedLimit) {
        return findRecent(UserContextHolder.currentTenantId(), workflowId, requestedLimit);
    }

    List<WorkflowRunEntity> findRecent(String tenantId, String workflowId, int requestedLimit) {
        int historySize = Math.max(1, Math.min(MAX_HISTORY_SIZE, requestedLimit));
        return workflowRunMapper.selectList(new LambdaQueryWrapper<WorkflowRunEntity>()
                .eq(WorkflowRunEntity::getTenantId, tenantId)
                .eq(WorkflowRunEntity::getWorkflowId, workflowId)
                .orderByDesc(WorkflowRunEntity::getCreatedAt)
                .last("limit " + historySize));
    }

    void recordStoredRun(String tenantId, String workflowId, String conversationId, Map<String, Object> inputs,
                         WorkflowRunResult result) {
        record(new RunRecord(tenantId, workflowId, conversationId, inputs, result));
    }

    void recordAdHocRun(String tenantId, String conversationId, Map<String, Object> inputs,
                        WorkflowRunResult result) {
        record(new RunRecord(tenantId, null, conversationId, inputs, result));
    }

    private void record(RunRecord record) {
        try {
            workflowRunMapper.insert(toEntity(record));
        } catch (Exception exception) {
            // A failed observability write must not turn a completed workflow into a failed run.
            log.warn("Could not persist workflow run {}: {}",
                    record.result().getRunId(), exception.getMessage());
        }
    }

    private static WorkflowRunEntity toEntity(RunRecord record) {
        WorkflowRunResult result = record.result();
        WorkflowRunEntity entity = new WorkflowRunEntity();
        entity.setId(result.getRunId());
        entity.setTenantId(record.tenantId());
        entity.setWorkflowId(record.workflowId());
        entity.setConversationId(record.conversationId());
        entity.setStatus(result.isSuccess() ? STATUS_SUCCESS : STATUS_FAILED);
        entity.setInputsJson(JsonUtils.toJson(record.inputs()));
        entity.setOutputsJson(JsonUtils.toJson(result.getOutputs()));
        entity.setStepsJson(JsonUtils.toJson(result.getSteps()));
        entity.setError(result.getError());
        return entity;
    }

    private record RunRecord(
            String tenantId,
            String workflowId,
            String conversationId,
            Map<String, Object> inputs,
            WorkflowRunResult result) {
    }
}
