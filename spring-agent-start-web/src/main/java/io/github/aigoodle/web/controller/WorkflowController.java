package io.github.aigoodle.web.controller;

import io.github.aigoodle.web.common.ApiResponse;
import io.github.aigoodle.web.common.SseEmitterBridge;
import io.github.aigoodle.web.dto.WorkflowRunRequest;
import io.github.aigoodle.web.dto.WorkflowSaveRequest;
import io.github.aigoodle.workflow.engine.WorkflowRunResult;
import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.agent.service.AgentService;
import io.github.aigoodle.workflow.entity.WorkflowEntity;
import io.github.aigoodle.workflow.entity.WorkflowRunEntity;
import io.github.aigoodle.workflow.graph.NodeType;
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

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST facade over {@link WorkflowService}. Provides:
 * <ul>
 *   <li>{@code /workflows} — save / list stored workflow definitions</li>
 *   <li>{@code /workflows/{id}/run} + {@code /workflows/{id}/run/stream}</li>
 *   <li>{@code /workflows/run-graph} + {@code /workflows/run-graph/stream}
 *       — one-shot ad-hoc run (for the canvas' "Run" button)</li>
 *   <li>{@code /node-types} — node metadata for the visual canvas</li>
 * </ul>
 * The SSE variants push per-node events in real time via the engine's step listener
 * hook, not by replaying at the end — so users see progress as it happens.
 */
@RestController
@ConditionalOnBean(WorkflowService.class)
@RequestMapping("${spring-agent.web.base-path:}")
public class WorkflowController {

    private final WorkflowService workflowService;
    private final io.github.aigoodle.web.service.WorkflowExampleService workflowExampleService;
    /**
     * Agent service is optional — this controller ships in the web module which
     * doesn't hard-depend on the agent module. When present, publishing a draft
     * repoints the owning app's {@code workflow_id} at the new snapshot.
     */
    private final ObjectProvider<AgentService> agentServiceProvider;

    public WorkflowController(WorkflowService workflowService,
                              io.github.aigoodle.web.service.WorkflowExampleService workflowExampleService,
                              ObjectProvider<AgentService> agentServiceProvider) {
        this.workflowService = workflowService;
        this.workflowExampleService = workflowExampleService;
        this.agentServiceProvider = agentServiceProvider;
    }

    // ----------------------------------------------------------------- CRUD

    @GetMapping("/workflows")
    public ApiResponse<List<WorkflowEntity>> list(@RequestParam(required = false) String tenantId) {
        return ApiResponse.ok(workflowService.list(tenantId));
    }

    /**
     * Save (upsert) a workflow. {@code req.appId} is required — the row's id
     * is pinned to it so subsequent saves for the same app hit the same PK
     * ("always exactly one draft per app" invariant). Only publish creates a
     * fresh row. A blank {@code appId} short-circuits with
     * {@code app_id_required} rather than reaching the service layer, so the
     * frontend gets an actionable error.
     */
    @PostMapping("/workflows")
    public ApiResponse<WorkflowEntity> save(@RequestBody WorkflowSaveRequest req) {
        if (req.getAppId() == null || req.getAppId().isBlank()) {
            return ApiResponse.error("app_id_required",
                    "Field 'appId' is required. Every workflow save is scoped to an app.");
        }
        WorkflowEntity entity = workflowService.save(
                req.getAppId(), req.getTenantId(), req.getName(), req.getMode(), req.getGraph());
        return ApiResponse.ok(entity);
    }

    @GetMapping("/workflows/{id}")
    public ApiResponse<WorkflowEntity> get(@PathVariable String id) {
        return ApiResponse.ok(workflowService.require(id));
    }

    /** Overwrite an existing workflow (name/mode/graph). Version bumps automatically. */
    @PutMapping("/workflows/{id}")
    public ApiResponse<WorkflowEntity> update(@PathVariable String id, @RequestBody WorkflowSaveRequest req) {
        return ApiResponse.ok(workflowService.update(id, req.getName(), req.getMode(), req.getGraph()));
    }

    @DeleteMapping("/workflows/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        workflowService.delete(id);
        return ApiResponse.ok();
    }

    // -------------------------------------------------- app-scoped draft flow
    // Dify-parity endpoints: the app owns exactly one draft workflow (id ==
    // appId invariant). Publishing snapshots the draft into a new immutable
    // row; the draft row itself is never replaced so the frontend keeps
    // hitting the same predictable id.

    /**
     * Fetch the draft workflow for an app — the graph the designer edits.
     * <p>Self-heals for legacy apps: if the app row exists and its mode is
     * {@code workflow}/{@code chatflow} but the draft row is missing (e.g. it
     * was created before this pairing logic landed, or the workflow module was
     * added to the classpath after the fact), we mint the draft on the fly
     * with the same {@code id == appId} invariant. This turns "open the
     * designer for an existing flow app" into a first-class success path
     * instead of a {@code draft_not_found} the frontend has to swallow.
     */
    @GetMapping("/apps/{appId}/workflow/draft")
    public ApiResponse<WorkflowEntity> getDraft(@PathVariable String appId) {
        WorkflowEntity draft = ensureDraft(appId);
        return draft == null
                ? ApiResponse.error("draft_not_found", "No draft workflow for app " + appId)
                : ApiResponse.ok(draft);
    }

    /**
     * Overwrite the draft's graph. Payload matches {@link WorkflowSaveRequest}
     * so the visual designer's "save draft" button can reuse the same body it
     * uses for the JSON playground. Auto-creates the draft row if it is
     * missing (same self-heal as {@link #getDraft(String)}) so first-save from
     * a legacy app doesn't 500.
     */
    @PutMapping("/apps/{appId}/workflow/draft")
    public ApiResponse<WorkflowEntity> saveDraft(@PathVariable String appId,
                                                 @RequestBody WorkflowSaveRequest req) {
        ensureDraft(appId);
        return ApiResponse.ok(workflowService.saveDraft(appId, req.getGraph(), null, null, null));
    }

    /**
     * Publish the current draft as an immutable snapshot and set
     * {@code apps.workflow_id} to the new snapshot's id.
     */
    @PostMapping("/apps/{appId}/workflow/publish")
    public ApiResponse<WorkflowEntity> publish(@PathVariable String appId,
                                               @RequestBody(required = false) PublishRequest req) {
        ensureDraft(appId);
        WorkflowEntity snapshot = workflowService.publishDraft(appId,
                req == null ? null : req.getMarkedName(),
                req == null ? null : req.getMarkedComment());
        // Point the app at the just-published snapshot so runtime consumers
        // pick it up. AgentService lives in a sibling module — only wire when
        // it's on the classpath, so this controller stays useful standalone.
        AgentService agentService = agentServiceProvider.getIfAvailable();
        if (agentService != null) {
            try {
                agentService.bindWorkflowId(appId, snapshot.getId());
            } catch (Exception ignored) {
                // App may have been deleted between publish and bind — the
                // snapshot itself is already durable, no need to fail the call.
            }
        }
        return ApiResponse.ok(snapshot);
    }

    /** List all workflow rows for an app — draft + snapshots, newest first. */
    @GetMapping("/apps/{appId}/workflows")
    public ApiResponse<List<WorkflowEntity>> listByApp(@PathVariable String appId) {
        return ApiResponse.ok(workflowService.listByApp(appId));
    }

    /**
     * Optional body for {@link #publish(String, PublishRequest)}. Both fields
     * are optional; when empty a timestamp-shaped version label is used.
     */
    @lombok.Data
    public static class PublishRequest {
        private String markedName;
        private String markedComment;
    }

    /** Recent runs for a workflow — powers the canvas' "run history" panel. */
    @GetMapping("/workflows/{id}/runs")
    public ApiResponse<List<WorkflowRunEntity>> runs(@PathVariable String id,
                                                      @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(workflowService.runs(id, limit));
    }

    // ------------------------------------------------------------------ run

    @PostMapping("/workflows/{id}/run")
    public ApiResponse<WorkflowRunResult> run(@PathVariable String id, @RequestBody WorkflowRunRequest req) {
        return ApiResponse.ok(workflowService.run(id, safeInputs(req), req.getConversationId()));
    }

    /** Run an ad-hoc graph (used by the canvas' preview / debug run). */
    @PostMapping("/workflows/run-graph")
    public ApiResponse<WorkflowRunResult> runGraph(@RequestBody WorkflowRunRequest req) {
        if (req.getGraph() == null) {
            return ApiResponse.error("graph_required", "Field 'graph' is required for ad-hoc runs");
        }
        return ApiResponse.ok(workflowService.runGraph(req.getGraph(), safeInputs(req), req.getConversationId()));
    }

    /**
     * SSE variant of {@link #runGraph(WorkflowRunRequest)}. Emits a {@code step} event
     * as each node completes and a {@code result} event when the run finishes. Errors
     * surface as {@code error} events. The stream is real-time — the engine's step
     * listener is wired directly into the reactive sink.
     */
    @PostMapping(value = "/workflows/run-graph/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runGraphStream(@RequestBody WorkflowRunRequest req) {
        return SseEmitterBridge.stream(emit -> {
            emit.event("run-start", Map.of("conversationId", req.getConversationId() == null ? "" : req.getConversationId()));
            WorkflowRunResult result = req.getGraph() != null
                    ? workflowService.runGraph(req.getGraph(), safeInputs(req), req.getConversationId(),
                            step -> emit.event("step", step))
                    : workflowService.run(req.getWorkflowId(), safeInputs(req), req.getConversationId(),
                            step -> emit.event("step", step));
            emit.event("result", result);
        });
    }

    /** SSE variant of the "run a stored workflow" endpoint. */
    @PostMapping(value = "/workflows/{id}/run/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runStream(@PathVariable String id, @RequestBody WorkflowRunRequest req) {
        return SseEmitterBridge.stream(emit -> {
            emit.event("run-start", Map.of("workflowId", id));
            WorkflowRunResult result = workflowService.run(id, safeInputs(req), req.getConversationId(),
                    step -> emit.event("step", step));
            emit.event("result", result);
        });
    }

    private static Map<String, Object> safeInputs(WorkflowRunRequest req) {
        return req.getInputs() == null ? new HashMap<>() : req.getInputs();
    }

    /**
     * Return the draft workflow for an app, materialising it on demand for
     * legacy or newly-adopted flow-mode apps. Returns {@code null} only when
     * the app row itself doesn't exist or isn't in a flow mode — the caller
     * decides whether to surface a friendlier error in that case.
     * <p>Uses {@link AgentService#require(String)} to read the app row (needed
     * for {@code tenantId} / {@code mode} / {@code name}); when the agent
     * module isn't on the classpath we can only report the existing draft,
     * since we have no way to prove the app is flow-mode.
     */
    private WorkflowEntity ensureDraft(String appId) {
        WorkflowEntity draft = workflowService.findDraft(appId);
        if (draft != null) return draft;
        AgentService agentService = agentServiceProvider.getIfAvailable();
        if (agentService == null) return null;
        AgentEntity app;
        try {
            app = agentService.require(appId);
        } catch (Exception ex) {
            return null;
        }
        if (!isFlowMode(app.getMode())) return null;
        WorkflowEntity created = workflowService.createDraft(
                app.getId(), app.getTenantId(), app.getMode(), app.getName(), null);
        // Keep apps.workflow_id in sync — mirrors what AgentController.create
        // does on first-time app creation. Errors here are non-fatal: the
        // draft itself is durable and future opens will re-attempt the bind.
        try {
            agentService.bindWorkflowId(app.getId(), created.getId());
        } catch (Exception ignored) {
        }
        return created;
    }

    private static boolean isFlowMode(String mode) {
        return "workflow".equals(mode) || "chatflow".equals(mode);
    }

    /**
     * Metadata for the frontend canvas: which node types exist plus a category tag so
     * the palette can group them.
     */
    @GetMapping("/node-types")
    public ApiResponse<List<Map<String, Object>>> nodeTypes() {
        return ApiResponse.ok(Arrays.stream(NodeType.values())
                .map(t -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("name", t.name());
                    view.put("category", categoryOf(t));
                    return view;
                }).toList());
    }

    /** Curated workflow examples for the playground's "示例" dropdown. */
    @GetMapping("/workflow-examples")
    public ApiResponse<List<io.github.aigoodle.web.service.WorkflowExampleService.WorkflowExample>> workflowExamples() {
        return ApiResponse.ok(workflowExampleService.examples());
    }

    private static String categoryOf(NodeType t) {
        return switch (t) {
            case START, END, ANSWER, IF_ELSE, ITERATION -> "flow";
            case TEMPLATE_TRANSFORM, VARIABLE_ASSIGNER, VARIABLE_AGGREGATOR, LIST_OPERATOR, CODE -> "data";
            case HTTP_REQUEST, SERVICE_API, DOCUMENT_EXTRACTOR, KNOWLEDGE_RETRIEVAL, TOOL -> "io";
            case LLM, AGENT, QUESTION_CLASSIFIER, PARAMETER_EXTRACTOR -> "llm";
        };
    }
}
