package io.github.aigoodle.agent.strategy;

import io.github.aigoodle.agent.api.AgentResponse;
import io.github.aigoodle.agent.runtime.AgentResumeCommand;

/** Strategy extension implemented only when execution can continue from an exact checkpoint. */
public interface ResumableAgentStrategy extends AgentStrategy {
    AgentResponse resume(AgentRunContext context, AgentResponse paused,
                         AgentResumeCommand command);
}
