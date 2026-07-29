package io.github.aigoodle.agent.api;

import lombok.Data;

/**
 * One step in an agent's reasoning trace (a ReAct thought/action/observation, a tool
 * call, or the final answer) — captured for observability and explainability.
 */
@Data
public class AgentStep {

    public enum Kind {
        THOUGHT, ACTION, OBSERVATION, TOOL_CALL, FINAL, APPROVAL, DELEGATION
    }

    private Kind kind;
    private String thought;
    private String action;
    private String actionInput;
    private String observation;
    private String content;

    public static AgentStep of(Kind kind, String content) {
        AgentStep s = new AgentStep();
        s.kind = kind;
        s.content = content;
        return s;
    }
}
