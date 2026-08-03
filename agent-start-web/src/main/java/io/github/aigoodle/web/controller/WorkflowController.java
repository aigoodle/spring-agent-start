package io.github.aigoodle.web.controller;

import io.github.aigoodle.agent.service.AgentService;
import io.github.aigoodle.web.common.ApiResponse;
import io.github.aigoodle.web.dto.WorkflowRunRequest;
import io.github.aigoodle.web.dto.WorkflowSaveRequest;
import io.github.aigoodle.web.service.WorkflowDraftCoordinator;
import io.github.aigoodle.web.service.WorkflowExampleService;
import io.github.aigoodle.web.support.WorkflowNodeCatalog;
import io.github.aigoodle.workflow.engine.WorkflowRunResult;
import io.github.aigoodle.workflow.entity.WorkflowEntity;
import io.github.aigoodle.workflow.entity.WorkflowRunEntity;
import io.github.aigoodle.workflow.service.WorkflowDraftDefinition;
import io.github.aigoodle.workflow.service.WorkflowService;
import lombok.Data;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP facade for workflow definitions, drafts, execution and palette metadata.
 * The SSE streaming run endpoints live in {@link WorkflowStreamController} —
 * they are servlet-only and skipped on reactive hosts.
 */
@RestController
@ConditionalOnBean(WorkflowService.class)
@RequestMapping("${spring-agent.web.base-path:}")
public class WorkflowController {

    private final WorkflowService workflowService;
    private final WorkflowExampleService workflowExampleService;
    private final WorkflowDraftCoordinator draftCoordinator;

    public WorkflowController(WorkflowService workflowService,
                              WorkflowExampleService workflowExampleService,
                              ObjectProvider<AgentService> agentServiceProvider) {
        this.workflowService = workflowService;
        this.workflowExampleService = workflowExampleService;
        this.draftCoordinator = new WorkflowDraftCoordinator(workflowService, agentServiceProvider);
    }

    @GetMapping("/workflows")
    public ApiResponse<List<WorkflowEntity>> list(@RequestParam(required = false) String tenantId) {
        return ApiResponse.ok(workflowService.list(tenantId));
    }

    @PostMapping("/workflows")
    public ApiResponse<WorkflowEntity> save(@RequestBody WorkflowSaveRequest request) {
        if (request.getAppId() == null || request.getAppId().isBlank()) {
            return ApiResponse.error(
                    "app_id_required",
                    "Field 'appId' is required. Every workflow save is scoped to an app.");
        }
        return ApiResponse.ok(workflowService.save(new WorkflowDraftDefinition(
                request.getAppId(),
                request.getTenantId(),
                request.getName(),
                request.getMode(),
                request.getGraph())));
    }

    @GetMapping("/workflows/{id}")
    public ApiResponse<WorkflowEntity> get(@PathVariable String id) {
        return ApiResponse.ok(workflowService.require(id));
    }

    @PutMapping("/workflows/{id}")
    public ApiResponse<WorkflowEntity> update(@PathVariable String id,
                                              @RequestBody WorkflowSaveRequest request) {
        return ApiResponse.ok(workflowService.update(
                id, request.getName(), request.getMode(), request.getGraph()));
    }

    @DeleteMapping("/workflows/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        workflowService.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/apps/{appId}/workflow/draft")
    public ApiResponse<WorkflowEntity> getDraft(@PathVariable String appId) {
        WorkflowEntity draft = draftCoordinator.findOrCreate(appId);
        return draft == null
                ? ApiResponse.error("draft_not_found", "No draft workflow for app " + appId)
                : ApiResponse.ok(draft);
    }

    @PutMapping("/apps/{appId}/workflow/draft")
    public ApiResponse<WorkflowEntity> saveDraft(@PathVariable String appId,
                                                 @RequestBody WorkflowSaveRequest request) {
        return ApiResponse.ok(draftCoordinator.save(appId, request.getGraph()));
    }

    @PostMapping("/apps/{appId}/workflow/publish")
    public ApiResponse<WorkflowEntity> publish(@PathVariable String appId,
                                               @RequestBody(required = false) PublishRequest request) {
        String markedName = request == null ? null : request.getMarkedName();
        String markedComment = request == null ? null : request.getMarkedComment();
        return ApiResponse.ok(draftCoordinator.publish(appId, markedName, markedComment));
    }

    @GetMapping("/apps/{appId}/workflows")
    public ApiResponse<List<WorkflowEntity>> listByApp(@PathVariable String appId) {
        return ApiResponse.ok(workflowService.listByApp(appId));
    }

    @GetMapping("/workflows/{id}/runs")
    public ApiResponse<List<WorkflowRunEntity>> runs(@PathVariable String id,
                                                      @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(workflowService.runs(id, limit));
    }

    @PostMapping("/workflows/{id}/run")
    public ApiResponse<WorkflowRunResult> run(@PathVariable String id,
                                              @RequestBody WorkflowRunRequest request) {
        return ApiResponse.ok(workflowService.run(
                id, inputsOf(request), request.getConversationId()));
    }

    @PostMapping("/workflows/run-graph")
    public ApiResponse<WorkflowRunResult> runGraph(@RequestBody WorkflowRunRequest request) {
        if (request.getGraph() == null) {
            return ApiResponse.error("graph_required", "Field 'graph' is required for ad-hoc runs");
        }
        return ApiResponse.ok(workflowService.runGraph(
                request.getGraph(), inputsOf(request), request.getConversationId()));
    }

    @GetMapping("/node-types")
    public ApiResponse<List<Map<String, Object>>> nodeTypes() {
        return ApiResponse.ok(WorkflowNodeCatalog.entries());
    }

    @GetMapping("/workflow-examples")
    public ApiResponse<List<WorkflowExampleService.WorkflowExample>> workflowExamples() {
        return ApiResponse.ok(workflowExampleService.examples());
    }

    private static Map<String, Object> inputsOf(WorkflowRunRequest request) {
        return request.getInputs() == null ? new HashMap<>() : request.getInputs();
    }

    @Data
    public static class PublishRequest {
        private String markedName;
        private String markedComment;
    }
}
