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
    private WorkflowWaitRequest waitRequest;
    private Long tokenCount;
    private String cost;
    private Integer externalStatus;

    public NodeResult output(String key, Object value) {
        outputs.put(key, value);
        return this;
    }

    public NodeResult handle(String handle) {
        this.handle = handle;
        return this;
    }

    public NodeResult usage(Long tokenCount, String cost) {
        this.tokenCount = tokenCount;
        this.cost = cost;
        return this;
    }

    public NodeResult externalStatus(Integer externalStatus) {
        this.externalStatus = externalStatus;
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

    public static NodeResult waiting(WorkflowWaitRequest request) {
        NodeResult result = new NodeResult();
        result.waitRequest = request;
        return result;
    }

    public boolean isWaiting() {
        return waitRequest != null;
    }
}
