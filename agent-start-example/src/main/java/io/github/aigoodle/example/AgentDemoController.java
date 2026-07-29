package io.github.aigoodle.example;

import io.github.aigoodle.agent.api.AgentRequest;
import io.github.aigoodle.agent.api.AgentResponse;
import io.github.aigoodle.agent.api.AgentStrategyType;
import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.agent.service.AgentService;
import io.github.aigoodle.agent.service.CreateAgentRequest;
import io.github.aigoodle.tool.ToolRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Demonstrates the tool ecosystem and the agent runtime.
 */
@RestController
public class AgentDemoController {

    private final AgentService agentService;
    private final ToolRegistry toolRegistry;

    public AgentDemoController(AgentService agentService, ToolRegistry toolRegistry) {
        this.agentService = agentService;
        this.toolRegistry = toolRegistry;
    }

    /** List the tools available to agents. */
    @GetMapping("/tools")
    public List<String> tools() {
        return toolRegistry.names();
    }

    /** Create an agent. {@code tools} is a comma-separated list of tool names (empty = all). */
    @PostMapping("/agents")
    public String createAgent(@RequestParam String name,
                              @RequestParam String modelProvider,
                              @RequestParam String modelName,
                              @RequestParam(defaultValue = "REACT") AgentStrategyType strategy,
                              @RequestParam(required = false) String instructions,
                              @RequestParam(required = false) String tools) {
        List<String> toolNames = (tools == null || tools.isBlank())
                ? List.of() : Arrays.stream(tools.split(",")).map(String::trim).toList();
        AgentEntity agent = agentService.create(CreateAgentRequest.builder()
                .name(name).modelProvider(modelProvider).modelName(modelName).strategy(strategy)
                .instructions(instructions == null ? "You are a helpful assistant. Use tools when useful." : instructions)
                .toolNames(toolNames).memoryEnabled(true).build());
        return agent.getId();
    }

    /** Chat with an agent; pass a stable conversationId to use memory across turns. */
    @PostMapping("/agents/{id}/chat")
    public Map<String, Object> chat(@PathVariable String id,
                                    @RequestParam String message,
                                    @RequestParam(required = false) String conversationId) {
        AgentResponse r = agentService.run(id,
                AgentRequest.builder().query(message).conversationId(conversationId).build());
        return Map.of(
                "answer", r.getText() == null ? "" : r.getText(),
                "status", r.getStatus().name(),
                "conversationId", r.getConversationId(),
                "steps", r.getSteps().size());
    }
}
