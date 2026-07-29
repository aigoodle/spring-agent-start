package io.github.aigoodle.agent.api;

/**
 * The reasoning strategy an agent uses.
 */
public enum AgentStrategyType {

    /** Manual Thought→Action→Observation loop with text parsing (works on any model). */
    REACT,

    /** Native tool-calling: the model decides tool calls, Spring AI runs the loop. */
    FUNCTION_CALLING,

    /** Plan first (decompose into steps), then execute each step, then synthesize. */
    PLAN_EXECUTE
}
