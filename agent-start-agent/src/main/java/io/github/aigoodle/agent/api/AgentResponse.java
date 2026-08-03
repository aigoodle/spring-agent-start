package io.github.aigoodle.agent.api;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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

    public static AgentResponse forConversation(String conversationId) {
        AgentResponse response = new AgentResponse();
        response.setConversationId(conversationId);
        return response;
    }

    public AgentResponse addStep(AgentStep step) {
        if (step != null) {
            steps.add(step);
        }
        return this;
    }

    public AgentResponse complete(String answer) {
        this.status = Status.COMPLETED;
        this.text = answer;
        this.error = null;
        this.pendingApproval = null;
        return this;
    }

    public AgentResponse awaitApproval(PendingApproval approval) {
        this.status = Status.AWAITING_APPROVAL;
        this.text = null;
        this.error = null;
        this.pendingApproval = Objects.requireNonNull(approval, "approval must not be null");
        return this;
    }

    public AgentResponse stopAfterMaxIterations(int maximumIterations) {
        this.status = Status.MAX_ITERATIONS;
        this.text = "Stopped after " + maximumIterations
                + " iterations without a final answer.";
        this.error = null;
        this.pendingApproval = null;
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

        public static PendingApproval forTool(
                String approvalId, String toolName, String toolInput) {
            PendingApproval approval = new PendingApproval();
            approval.setApprovalId(approvalId);
            approval.setToolName(toolName);
            approval.setToolInput(toolInput);
            return approval;
        }
    }
}
