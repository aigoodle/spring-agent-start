package io.github.aigoodle.web.controller;

import io.github.aigoodle.agent.api.AgentMessage;
import io.github.aigoodle.agent.api.AgentRequest;
import io.github.aigoodle.agent.api.AgentResponse;
import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.agent.service.AgentService;
import io.github.aigoodle.agent.service.CreateAgentRequest;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.tool.AgentTool;
import io.github.aigoodle.tool.ToolRegistry;
import io.github.aigoodle.web.common.ApiResponse;
import io.github.aigoodle.web.common.SseEmitterBridge;
import io.github.aigoodle.web.dto.ChatRequest;
import io.github.aigoodle.workflow.entity.WorkflowEntity;
import io.github.aigoodle.workflow.service.WorkflowService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST facade over {@link AgentService}. Handles agent CRUD, one-shot chat and a
 * server-sent-event stream that emits per-step traces and the final answer — which is
 * how the frontend renders a chat conversation.
 * <p>
 * NOTE: the agent strategies (ReAct etc.) currently accumulate steps in memory and
 * return them together with the final response. A follow-up refactor will thread a
 * step listener through the {@code AgentRunContext} so this stream becomes truly
 * real-time (per-thought push) rather than replay.
 */
@RestController
@ConditionalOnBean(AgentService.class)
@RequestMapping("${spring-agent.web.base-path:}/agents")
public class AgentController {

    private final AgentService agentService;
    /**
     * Tool registry is optional — the agent module can run without the tools
     * module on the classpath. When absent, the tools endpoint returns empty.
     */
    private final ObjectProvider<ToolRegistry> toolRegistryProvider;
    /**
     * Workflow service is optional — the agent module doesn't depend on the
     * workflow module. When present, workflow-mode / chatflow-mode app creates
     * are transparently paired with a draft workflow row (id == appId).
     */
    private final ObjectProvider<WorkflowService> workflowServiceProvider;

    public AgentController(AgentService agentService,
                           ObjectProvider<ToolRegistry> toolRegistryProvider,
                           ObjectProvider<WorkflowService> workflowServiceProvider) {
        this.agentService = agentService;
        this.toolRegistryProvider = toolRegistryProvider;
        this.workflowServiceProvider = workflowServiceProvider;
    }

    // ------------------------------------------------------------------ CRUD

    @GetMapping
    public ApiResponse<List<AgentEntity>> list(@RequestParam(required = false) String tenantId) {
        List<AgentEntity> rows = agentService.list(tenantId);
        // Enrich each catalog row with its sidecar so the agent-list cards can
        // render 策略 / 迭代次数 chips and the drawer prefetch has the full form.
        rows.forEach(agentService::enrich);
        return ApiResponse.ok(rows);
    }

    @GetMapping("/{id}")
    public ApiResponse<AgentEntity> get(@PathVariable String id) {
        return ApiResponse.ok(agentService.enrich(agentService.require(id)));
    }

    /**
     * Full "编排" drawer payload for an app — the {@link
     * io.github.aigoodle.agent.entity.AppModelConfig} sidecar row keyed by
     * app id. Front-end fetches this on drawer open so the sliders /
     * thinkingMode toggles hydrate from persisted values instead of resetting
     * to provider defaults every time.
     */
    @GetMapping("/{id}/model-config")
    public ApiResponse<io.github.aigoodle.agent.entity.AppModelConfig> modelConfig(@PathVariable String id) {
        agentService.require(id);
        return ApiResponse.ok(agentService.getModelConfig(id));
    }

    @PostMapping
    public ApiResponse<AgentEntity> create(@RequestBody CreateAgentRequest req) {
        AgentEntity entity = agentService.create(req);
        // Workflow / chatflow apps get a paired draft workflow row minted on
        // the spot (Dify parity). The draft's primary key mirrors the app id
        // so the frontend can GET /apps/{appId}/workflow/draft without a lookup.
        // We only wire this when the workflow module is on the classpath; agent
        // module stays independent.
        if (isFlowMode(entity.getMode())) {
            WorkflowService workflowService = workflowServiceProvider.getIfAvailable();
            if (workflowService != null) {
                workflowService.createDraft(
                        entity.getId(), entity.getTenantId(), entity.getMode(), entity.getName(), null);
                // Point the app at its draft so single-shot loads have the fk
                // ready (published snapshots later overwrite this pointer).
                entity = agentService.bindWorkflowId(entity.getId(), entity.getId());
            }
        }
        return ApiResponse.ok(agentService.enrich(entity));
    }

    private static boolean isFlowMode(String mode) {
        return "workflow".equals(mode) || "chatflow".equals(mode);
    }

    @PutMapping("/{id}")
    public ApiResponse<AgentEntity> update(@PathVariable String id, @RequestBody CreateAgentRequest req) {
        return ApiResponse.ok(agentService.enrich(agentService.update(id, req)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        agentService.delete(id);
        return ApiResponse.ok();
    }

    /**
     * Return the tool descriptors an agent has been configured with. Useful in the
     * chat UI to show a "🔧 tools this agent can call" strip so users know what to
     * ask. When the tools module is not on the classpath, returns an empty list.
     */
    @GetMapping("/{id}/tools")
    public ApiResponse<List<Map<String, Object>>> tools(@PathVariable String id) {
        agentService.require(id);
        io.github.aigoodle.agent.entity.AppModelConfig cfg = agentService.getModelConfig(id);
        List<String> names = cfg == null ? List.of()
                : JsonUtils.parseList(cfg.getToolNamesJson(), String.class);
        if (names == null || names.isEmpty()) {
            return ApiResponse.ok(List.of());
        }
        ToolRegistry registry = toolRegistryProvider.getIfAvailable();
        if (registry == null) {
            return ApiResponse.ok(List.of());
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (String name : names) {
            if (!registry.has(name)) continue;
            AgentTool t = registry.get(name);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", t.name());
            view.put("description", t.description());
            view.put("inputSchema", t.inputSchema());
            out.add(view);
        }
        return ApiResponse.ok(out);
    }

    // ------------------------------------------------------------------ chat

    /**
     * Read history for a conversation. Used by the chat UI on refresh so the user's
     * ongoing conversation isn't lost. The URL includes the agent id even though
     * memory is keyed by conversationId alone — cleaner REST hierarchy.
     */
    @GetMapping("/{id}/conversations/{conversationId}/messages")
    public ApiResponse<List<AgentMessage>> history(@PathVariable String id,
                                                   @PathVariable String conversationId,
                                                   @RequestParam(defaultValue = "100") int max) {
        return ApiResponse.ok(agentService.history(conversationId, max));
    }

    @PostMapping("/{id}/chat")
    public ApiResponse<AgentResponse> chat(@PathVariable String id, @RequestBody ChatRequest req) {
        AgentRequest ar = AgentRequest.builder()
                .query(req.getQuery())
                .conversationId(req.getConversationId())
                .variables(req.getVariables())
                .build();
        return ApiResponse.ok(agentService.run(id, ar));
    }

    /**
     * SSE chat. Emits one {@code step} event as soon as each reasoning step is
     * finalised — driven by {@code AgentRunContext#stepListener} threaded through
     * every strategy — plus a final {@code result} event with the full response.
     * Errors surface as {@code error} events. Runs on a virtual thread via
     * {@link SseEmitterBridge}; each connection consumes one Tomcat async slot.
     */
    @PostMapping(value = "/{id}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@PathVariable String id, @RequestBody ChatRequest req) {
        return SseEmitterBridge.stream(emit -> {
            emit.event("chat-start", java.util.Map.of("agentId", id));
            AgentRequest ar = AgentRequest.builder()
                    .query(req.getQuery())
                    .conversationId(req.getConversationId())
                    .variables(req.getVariables())
                    .build();
            AgentResponse response = agentService.run(id, ar, step -> emit.event("step", step));
            emit.event("result", response);
        });
    }
}
