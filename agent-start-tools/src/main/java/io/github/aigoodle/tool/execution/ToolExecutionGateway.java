package io.github.aigoodle.tool.execution;

import io.github.aigoodle.tool.AgentTool;

import java.util.Map;

/** Single governed execution boundary for every tool invocation. */
@FunctionalInterface
public interface ToolExecutionGateway {
    Object execute(AgentTool tool, Map<String, Object> arguments, ToolExecutionContext context);

    static ToolExecutionGateway direct() {
        return (tool, arguments, context) -> tool.execute(arguments == null ? Map.of() : arguments);
    }
}
