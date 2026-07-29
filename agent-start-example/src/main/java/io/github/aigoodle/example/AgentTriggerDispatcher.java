package io.github.aigoodle.example;

import io.github.aigoodle.agent.api.AgentRequest;
import io.github.aigoodle.agent.api.AgentResponse;
import io.github.aigoodle.agent.service.AgentService;
import io.github.aigoodle.trigger.dispatch.DispatchResult;
import io.github.aigoodle.trigger.dispatch.TriggerDispatcher;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Application-supplied dispatcher that lets triggers target an <em>agent</em> (not just
 * a workflow) — a one-class demonstration of the trigger module's {@code TriggerDispatcher}
 * SPI, which keeps the trigger module decoupled from the agent module.
 */
@Component
public class AgentTriggerDispatcher implements TriggerDispatcher {

    private final AgentService agentService;

    public AgentTriggerDispatcher(AgentService agentService) {
        this.agentService = agentService;
    }

    @Override
    public String targetType() {
        return "agent";
    }

    @Override
    public DispatchResult dispatch(String targetId, Map<String, Object> inputs, String conversationId) {
        Object query = inputs.getOrDefault("query", inputs.values().stream().findFirst().orElse(""));
        AgentResponse response = agentService.run(targetId,
                AgentRequest.builder().query(String.valueOf(query)).conversationId(conversationId).build());
        return DispatchResult.ok(response.getConversationId(),
                Map.of("answer", response.getText() == null ? "" : response.getText(),
                        "status", response.getStatus().name()));
    }
}
