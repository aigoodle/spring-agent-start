package io.github.aigoodle.web.controller;

import io.github.aigoodle.agent.api.AgentMessage;
import io.github.aigoodle.agent.api.AgentResponse;
import io.github.aigoodle.agent.service.AgentService;
import io.github.aigoodle.web.common.ApiResponse;
import io.github.aigoodle.web.dto.ChatRequest;
import io.github.aigoodle.web.support.AgentRequestMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Synchronous conversation endpoints for agent applications. The SSE streaming
 * chat endpoint lives in {@link AgentChatStreamController} — it is servlet-only
 * and skipped on reactive hosts.
 */
@RestController
@ConditionalOnBean(AgentService.class)
@RequestMapping("${spring-agent.web.base-path:}/agents")
public class AgentChatController {

    private final AgentService agentService;

    public AgentChatController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping("/{id}/conversations/{conversationId}/messages")
    public ApiResponse<List<AgentMessage>> history(
            @PathVariable String id,
            @PathVariable String conversationId,
            @RequestParam(defaultValue = "100") int max) {
        return ApiResponse.ok(agentService.history(conversationId, max));
    }

    @PostMapping("/{id}/chat")
    public ApiResponse<AgentResponse> chat(
            @PathVariable String id, @RequestBody ChatRequest request) {
        return ApiResponse.ok(agentService.run(id, AgentRequestMapper.from(request)));
    }
}
