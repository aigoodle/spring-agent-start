package io.github.aigoodle.workflow.node;

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
}
