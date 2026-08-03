package io.github.aigoodle.workflow.node;

import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import lombok.Data;

import java.util.Map;

/**
 * One executed node, captured for observability / debugging / replay.
 */
@Data
public class StepRecord {

    private String nodeId;
    private NodeType nodeType;
    private String title;
    private Map<String, Object> outputs;
    private String handle;
    private long elapsedMillis;
    private boolean failed;
    private String error;

    public static StepRecord completed(NodeDef node, NodeResult result, long elapsedMillis) {
        StepRecord step = new StepRecord();
        step.nodeId = node.getId();
        step.nodeType = node.getType();
        step.title = node.getTitle();
        step.outputs = result.getOutputs();
        step.handle = result.getHandle();
        step.elapsedMillis = elapsedMillis;
        step.failed = result.isFailed();
        step.error = result.getError();
        return step;
    }
}
