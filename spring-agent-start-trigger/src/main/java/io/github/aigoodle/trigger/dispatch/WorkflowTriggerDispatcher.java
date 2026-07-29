package io.github.aigoodle.trigger.dispatch;

import io.github.aigoodle.workflow.engine.WorkflowRunResult;
import io.github.aigoodle.workflow.service.WorkflowService;

import java.util.Map;

/**
 * Default dispatcher: runs a stored workflow by id with the trigger payload as inputs.
 */
public class WorkflowTriggerDispatcher implements TriggerDispatcher {

    public static final String TARGET_TYPE = "workflow";

    private final WorkflowService workflowService;

    public WorkflowTriggerDispatcher(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @Override
    public String targetType() {
        return TARGET_TYPE;
    }

    @Override
    public DispatchResult dispatch(String targetId, Map<String, Object> inputs, String conversationId) {
        WorkflowRunResult result = workflowService.run(targetId, inputs, conversationId);
        if (result.isSuccess()) {
            return DispatchResult.ok(result.getRunId(), result.getOutputs());
        }
        DispatchResult failed = DispatchResult.failed(result.getError());
        failed.setRunId(result.getRunId());
        return failed;
    }
}
