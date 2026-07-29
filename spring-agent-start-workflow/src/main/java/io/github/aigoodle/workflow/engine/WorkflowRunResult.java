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

    /** Convenience accessor for a single named output. */
    public Object output(String key) {
        return outputs == null ? null : outputs.get(key);
    }

    public String text() {
        Object v = output("text");
        if (v == null && outputs != null && outputs.size() == 1) {
            v = outputs.values().iterator().next();
        }
        return v == null ? null : String.valueOf(v);
    }
}
