package io.github.aigoodle.workflow.node;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * The outcome of executing one node: its named outputs and, for branching nodes, the
 * {@code handle} of the chosen outgoing edge.
 */
@Data
public class NodeResult {

    private final Map<String, Object> outputs = new HashMap<>();

    /** Selected branch handle (e.g. {@code "true"}/{@code "false"}); null = default. */
    private String handle;

    private boolean failed;
    private String error;

    public NodeResult output(String key, Object value) {
        outputs.put(key, value);
        return this;
    }

    public NodeResult handle(String handle) {
        this.handle = handle;
        return this;
    }

    public static NodeResult of(String key, Object value) {
        return new NodeResult().output(key, value);
    }

    public static NodeResult empty() {
        return new NodeResult();
    }

    public static NodeResult failure(String error) {
        NodeResult result = new NodeResult();
        result.failed = true;
        result.error = error;
        return result;
    }
}
