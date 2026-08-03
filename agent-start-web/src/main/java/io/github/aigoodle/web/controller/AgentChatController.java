package io.github.aigoodle.web.controller;

import io.github.aigoodle.agent.api.AgentMessage;
import io.github.aigoodle.agent.api.AgentResponse;
import io.github.aigoodle.agent.service.AgentService;
import io.github.aigoodle.web.common.ApiResponse;
import io.github.aigoodle.web.common.SseEmitterBridge;
import io.github.aigoodle.web.dto.ChatRequest;
import io.github.aigoodle.web.support.AgentRequestMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/** Synchronous and streaming conversation endpoints for agent applications. */
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

    @PostMapping(value = "/{id}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@PathVariable String id, @RequestBody ChatRequest request) {
        return SseEmitterBridge.stream(emitter -> {
            emitter.event("chat-start", Map.of("agentId", id));
            AgentResponse response = agentService.run(
                    id,
                    AgentRequestMapper.from(request),
                    step -> emitter.event("step", step));
            emitter.event("result", response);
        });
    }
}
