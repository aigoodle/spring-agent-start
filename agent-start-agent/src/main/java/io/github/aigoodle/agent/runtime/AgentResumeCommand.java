package io.github.aigoodle.agent.runtime;

/** Human decision that resumes a run paused at an approval checkpoint. */
public record AgentResumeCommand(String approvalId, Decision decision) {
    public enum Decision { APPROVE, DENY }

    public AgentResumeCommand {
        if (approvalId == null || approvalId.isBlank()) {
            throw new IllegalArgumentException("approvalId must not be blank");
        }
        if (decision == null) {
            throw new IllegalArgumentException("decision must not be null");
        }
    }
}
