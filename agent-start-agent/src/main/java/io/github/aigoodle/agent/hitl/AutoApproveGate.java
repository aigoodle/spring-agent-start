package io.github.aigoodle.agent.hitl;

/**
 * Default gate that approves everything — i.e. HITL is opt-in. Replace with a bean
 * that returns {@link Decision#PENDING} to require human sign-off.
 */
public class AutoApproveGate implements ApprovalGate {

    @Override
    public Decision review(ToolCall toolCall) {
        return Decision.APPROVE;
    }
}
