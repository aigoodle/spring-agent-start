package io.github.aigoodle.workflow.engine;

import io.github.aigoodle.workflow.node.StepRecord;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * The outcome of a whole workflow run: the END node's outputs, every executed step
 * (for observability) and overall success/error.
 */
@Data
public class WorkflowRunResult {

    private String runId;
    private boolean success;
    private String error;
    private Map<String, Object> outputs;
    private List<StepRecord> steps;

    public static WorkflowRunResult forRun(String runId, List<StepRecord> steps) {
        WorkflowRunResult result = new WorkflowRunResult();
        result.runId = runId;
        result.steps = steps;
        return result;
    }

    public WorkflowRunResult succeed(Map<String, Object> outputs) {
        this.success = true;
        this.error = null;
        this.outputs = outputs;
        return this;
    }

    public WorkflowRunResult fail(String error, Map<String, Object> outputs) {
        this.success = false;
        this.error = error;
        this.outputs = outputs;
        return this;
    }

    /** Convenience accessor for a single named output. */
    public Object output(String key) {
        return outputs == null ? null : outputs.get(key);
    }

    public String text() {
        Object textOutput = output("text");
        if (textOutput == null && outputs != null && outputs.size() == 1) {
            textOutput = outputs.values().iterator().next();
        }
        return textOutput == null ? null : String.valueOf(textOutput);
    }
}
