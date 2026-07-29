package io.github.aigoodle.agent.multiagent;

import io.github.aigoodle.agent.api.AgentRequest;
import io.github.aigoodle.agent.api.AgentResponse;
import io.github.aigoodle.tool.AbstractAgentTool;

import java.util.Map;
import java.util.function.BiFunction;

/**
 * Exposes a sub-agent as a tool, enabling multi-agent orchestration (an "orchestrator"
 * agent delegates a subtask to a specialist "worker" agent). The delegation runs the
 * sub-agent in its own conversation and returns its answer as the tool result.
 */
public class AgentDelegationTool extends AbstractAgentTool {

    private final String toolName;
    private final String description;
    private final String subAgentId;
    private final BiFunction<String, AgentRequest, AgentResponse> runner;

    public AgentDelegationTool(String toolName, String description, String subAgentId,
                               BiFunction<String, AgentRequest, AgentResponse> runner) {
        this.toolName = toolName;
        this.description = description;
        this.subAgentId = subAgentId;
        this.runner = runner;
    }

    @Override
    public String name() {
        return toolName;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\","
                + "\"description\":\"the task or question for the sub-agent\"}},\"required\":[\"input\"]}";
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String input = str(args, "input", str(args, "query", ""));
        AgentResponse response = runner.apply(subAgentId, AgentRequest.of(input));
        return response.getText() == null ? "" : response.getText();
    }
}
