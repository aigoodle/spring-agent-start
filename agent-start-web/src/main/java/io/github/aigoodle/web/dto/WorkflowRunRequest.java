package io.github.aigoodle.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.Map;

/**
 * Body accepted by both {@code /workflows/{id}/run} and {@code /workflows/run-graph}.
 * When {@code graph} is set it's an ad-hoc run; when {@code workflowId} is set it's a
 * run of a stored workflow.
 *
 * <p>{@code graph} is kept opaque ({@link JsonNode}) for the same reason as
 * {@link WorkflowSaveRequest#graph} — the visual designer produces UI-heavy
 * JSON with alias node types. The service layer canonicalises via
 * {@link io.github.aigoodle.workflow.graph.NodeType}'s {@code @JsonCreator}
 * when materialising the typed graph for the engine.</p>
 */
@Data
public class WorkflowRunRequest {

    private String workflowId;
    private JsonNode graph;
    /** Canonical START-node input object used by UI, API and durable triggers. */
    private Map<String, Object> data;
    /** @deprecated use {@link #data}; retained for wire compatibility. */
    @Deprecated
    private Map<String, Object> inputs;
    private String conversationId;
}
