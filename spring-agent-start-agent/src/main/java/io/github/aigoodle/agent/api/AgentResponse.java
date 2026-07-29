package io.github.aigoodle.agent.api;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * The result of an agent run, including the full reasoning trace and (for
 * human-in-the-loop) any pending approval that paused execution.
 */
@Data
public class AgentResponse {

    public enum Status {
        COMPLETED, AWAITING_APPROVAL, FAILED, MAX_ITERATIONS
    }

    private Status status = Status.COMPLETED;
    private String text;
    private String conversationId;
    private final List<AgentStep> steps = new ArrayList<>();
    private int iterations;
    private String error;

    /** Set when {@link Status#AWAITING_APPROVAL}: the tool call awaiting a human decision. */
    private PendingApproval pendingApproval;

    public AgentResponse addStep(AgentStep step) {
        steps.add(step);
        return this;
    }

    public boolean isCompleted() {
        return status == Status.COMPLETED;
    }

    @Data
    public static class PendingApproval {
        private String approvalId;
        private String toolName;
        private String toolInput;
    }
}
