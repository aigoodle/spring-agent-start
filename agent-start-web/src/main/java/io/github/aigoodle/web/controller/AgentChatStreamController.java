package io.github.aigoodle.web.controller;

import io.github.aigoodle.agent.api.AgentResponse;
import io.github.aigoodle.agent.service.AgentExecutionService;
import io.github.aigoodle.web.common.SseEmitterBridge;
import io.github.aigoodle.web.dto.ChatRequest;
import io.github.aigoodle.web.support.AgentRequestMapper;
import io.github.aigoodle.web.support.ChannelAdministrationPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * SSE streaming chat endpoint for agents.
 * <p>
 * MVC-only by design: the handler signature references {@link SseEmitter}, a
 * spring-webmvc type. The controller is therefore registered only when
 * spring-webmvc is on the classpath; on reactive hosts (e.g.
 * {@code agent-start-server}, which excludes {@code spring-boot-starter-web})
 * component scan skips it, and streaming chat is served by the
 * {@code agent-start-completion} module instead. Keeping the method out of
 * {@link AgentChatController} matters — WebFlux's handler mapping introspects
 * every registered controller's method signatures at startup and would fail on
 * the missing servlet class.
 */
@RestController
@ConditionalOnClass(SseEmitter.class)
@ConditionalOnBean(AgentExecutionService.class)
@RequestMapping("/agents")
public class AgentChatStreamController {

    private final AgentExecutionService executions;
    private final ChannelAdministrationPolicy administration;

    public AgentChatStreamController(AgentExecutionService executions, ChannelAdministrationPolicy administration) {
        this.executions = executions; this.administration = administration;
    }

    @PostMapping(value = "/{id}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@PathVariable String id, @RequestBody ChatRequest request) {
        String tenantId = io.github.aigoodle.common.context.UserContextHolder.currentTenantId();
        return SseEmitterBridge.stream(emitter -> {
            emitter.event("chat-start", Map.of("agentId", id));
            AgentResponse response = executions.runPublished(
                    tenantId, id, null,
                    AgentRequestMapper.from(request), step -> emitter.event("step", step), null);
            emitter.event("result", response);
        });
    }

    @PostMapping(value = "/{id}/chat/preview/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter previewStream(@PathVariable String id, @RequestBody ChatRequest request) {
        administration.requireAdministrator();
        String tenantId = io.github.aigoodle.common.context.UserContextHolder.currentTenantId();
        return SseEmitterBridge.stream(emitter -> {
            emitter.event("chat-start", Map.of("agentId", id, "preview", true));
            AgentResponse response = executions.previewDraft(
                    tenantId, id,
                    AgentRequestMapper.from(request), step -> emitter.event("step", step),
                    token -> emitter.event("token", token));
            emitter.event("result", response);
        });
    }
}
