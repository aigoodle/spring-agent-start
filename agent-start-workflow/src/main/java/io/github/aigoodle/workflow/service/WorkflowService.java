package io.github.aigoodle.workflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.workflow.engine.WorkflowEngine;
import io.github.aigoodle.workflow.engine.WorkflowRunResult;
import io.github.aigoodle.workflow.entity.WorkflowEntity;
import io.github.aigoodle.workflow.entity.WorkflowRunEntity;
import io.github.aigoodle.workflow.graph.WorkflowGraph;
import io.github.aigoodle.workflow.mapper.WorkflowMapper;
import io.github.aigoodle.workflow.mapper.WorkflowRunMapper;

import java.util.Map;
import java.util.function.Consumer;

import io.github.aigoodle.workflow.node.StepRecord;

/**
 * Stores workflow definitions and runs them, persisting each run for observability.
 * {@link #runGraph} runs an ad-hoc graph without persistence — handy for embedding
 * the engine directly in application code.
 *
 * <p>The persisted graph shape is an opaque {@link JsonNode} on
 * {@link WorkflowEntity#getGraph()} — MyBatis-Plus's
 * {@link com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler} handles
 * (de)serialisation onto the {@code workflows.graph} column. The typed
 * {@link WorkflowGraph} is only materialised when the engine actually runs a
 * graph, via {@link #graphOf(WorkflowEntity)}.</p>
 */
public class WorkflowService {

    private final WorkflowMapper workflowMapper;
    private final WorkflowRunMapper runMapper;
    private final WorkflowEngine engine;

    public WorkflowService(WorkflowMapper workflowMapper, WorkflowRunMapper runMapper, WorkflowEngine engine) {
        this.workflowMapper = workflowMapper;
        this.runMapper = runMapper;
        this.engine = engine;
    }

    /** App-scoped typed overload — programmatic call sites and tests. */
    public WorkflowEntity save(String appId, String tenantId, String name, String mode, WorkflowGraph graph) {
        return save(appId, tenantId, name, mode, graph == null ? null : toJsonNode(graph));
    }

    /**
     * Upsert the draft workflow for an app. The workflow row's primary key is
     * pinned to {@code appId}, so every save from the same app hits the same
     * row — there is at most one {@code version='draft'} record per app.
     * Publishing later takes a snapshot copy into a new row (see
     * {@link #publishDraft(String, String, String)}).
     *
     * <p>{@code appId} is <b>required</b>. A blank/null value is a caller
     * bug and throws {@code app_id_required} rather than silently minting a
     * fresh UUID row — the earlier fallback masked broken frontend payloads
     * (the "save button seemed to succeed but a duplicate row appeared each
     * time" symptom).</p>
     *
     * <p>Mirrors the legacy {@code spring-agent-start} contract
     * ({@code WorkflowServiceImpl.draft()}): every draft save is a
     * {@code saveEntity(item.setId(appId))} upsert.</p>
     */
    @org.springframework.transaction.annotation.Transactional
    public WorkflowEntity save(String appId, String tenantId, String name, String mode, JsonNode graph) {
        if (appId == null || appId.isBlank()) {
            throw new AgentException("app_id_required",
                    "appId is required — every workflow save must be scoped to an app "
                            + "(mirrors spring-agent-start's WorkflowServiceImpl.draft()).",
                    null);
        }
        WorkflowEntity existing = workflowMapper.selectById(appId);
        if (existing == null) {
            return insertDraft(appId, appId, tenantId, name, mode, graph);
        }
        if (name != null && !name.isBlank()) existing.setName(name);
        if (mode != null && !mode.isBlank()) existing.setMode(mode);
        if (graph != null) existing.setGraph(graph);
        workflowMapper.updateById(existing);
        return existing;
    }

    /**
     * Insert a new draft row. {@code id} + {@code appId} may both be null for
     * the standalone playground path (MyBatis-Plus mints a UUID); otherwise
     * they get pinned to the caller's chosen values.
     */
    private WorkflowEntity insertDraft(String id, String appId, String tenantId,
                                       String name, String mode, JsonNode graph) {
        WorkflowEntity entity = new WorkflowEntity();
        if (id != null) entity.setId(id);
        if (appId != null) entity.setAppId(appId);
        entity.setTenantId(tenantId);
        entity.setName(name);
        entity.setMode(mode == null ? "workflow" : mode);
        entity.setGraph(graph);
        entity.setVersion(VERSION_DRAFT);
        entity.setPublished(Boolean.FALSE);
        workflowMapper.insert(entity);
        return entity;
    }

    public WorkflowEntity require(String id) {
        WorkflowEntity entity = workflowMapper.selectById(id);
        if (entity == null) {
            throw new AgentException("workflow_not_found", "Workflow not found: " + id, null);
        }
        return entity;
    }

    public WorkflowGraph graphOf(WorkflowEntity entity) {
        return materializeGraph(entity.getGraph());
    }

    /** All saved workflows in a tenant, most recently updated first. */
    public java.util.List<WorkflowEntity> list(String tenantId) {
        return workflowMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkflowEntity>()
                .eq(WorkflowEntity::getTenantId, tenantId == null ? "default" : tenantId)
                .orderByDesc(WorkflowEntity::getUpdatedAt));
    }

    /** Convenience typed overload for programmatic use. */
    public WorkflowEntity update(String id, String name, String mode, WorkflowGraph graph) {
        return update(id, name, mode, graph == null ? null : toJsonNode(graph));
    }

    /** Overwrite an existing saved workflow, keeping its version string. */
    public WorkflowEntity update(String id, String name, String mode, JsonNode graph) {
        WorkflowEntity existing = require(id);
        existing.setName(name == null ? existing.getName() : name);
        existing.setMode(mode == null ? existing.getMode() : mode);
        if (graph != null) existing.setGraph(graph);
        workflowMapper.updateById(existing);
        return existing;
    }

    public void delete(String id) {
        workflowMapper.deleteById(id);
    }

    // ------------------------------------------------------------------ draft
    // Dify-parity draft-per-app model. Convention: the draft workflow row's
    // primary key equals the owning app's id (borrowed from the legacy
    // spring-agent-start `WorkflowServiceImpl.draft()`). This makes "find the
    // draft of app X" a single primary-key lookup — no secondary index needed —
    // and keeps `apps.workflow_id` free to point at whichever *published*
    // snapshot is currently active at runtime.

    /** Draft row id equals owning app id. */
    public static final String VERSION_DRAFT = "draft";

    /** Empty seed graph used when a flow-mode app first mints its draft row. */
    private static final String EMPTY_GRAPH_JSON = "{\"nodes\":[],\"edges\":[]}";

    /**
     * Create the initial draft workflow for an app. Called by the coordinating
     * controller right after the app row is inserted. Idempotent — a repeat
     * call for the same app id is a no-op (returns the existing row unchanged).
     * <p>Delegates to {@link #save(String, String, String, String, JsonNode)}
     * so first-save and subsequent-save go through the same upsert path.
     */
    @org.springframework.transaction.annotation.Transactional
    public WorkflowEntity createDraft(String appId, String tenantId, String mode, String name, WorkflowGraph seedGraph) {
        WorkflowEntity existing = workflowMapper.selectById(appId);
        if (existing != null) return existing;
        JsonNode graph = seedGraph == null ? emptyGraphNode() : toJsonNode(seedGraph);
        return insertDraft(appId, appId, tenantId == null ? "default" : tenantId,
                name, mode == null ? "workflow" : mode, graph);
    }

    /**
     * Return the draft workflow for an app, or null if the app was not created
     * with a draft (e.g. non-workflow modes). Uses the id==appId convention so
     * this is a primary-key hit.
     */
    public WorkflowEntity findDraft(String appId) {
        WorkflowEntity entity = workflowMapper.selectById(appId);
        if (entity != null && VERSION_DRAFT.equals(entity.getVersion())) {
            return entity;
        }
        return null;
    }

    /** Convenience typed overload. */
    @org.springframework.transaction.annotation.Transactional
    public WorkflowEntity saveDraft(String appId, WorkflowGraph graph, String features,
                                    String environmentVariables, String conversationVariables) {
        return saveDraft(appId, graph == null ? null : toJsonNode(graph),
                features, environmentVariables, conversationVariables);
    }

    /**
     * Overwrite the draft's graph (and optionally the side-car JSON columns).
     * Version stays {@code 'draft'} — this only mutates the existing row where
     * {@code id = appId}. Delegates the graph upsert to {@link #save(String,
     * String, String, String, JsonNode)} so the "always exactly one draft per
     * app" invariant is enforced in one place, then patches the side-cars.
     */
    @org.springframework.transaction.annotation.Transactional
    public WorkflowEntity saveDraft(String appId, JsonNode graph, String features,
                                    String environmentVariables, String conversationVariables) {
        // Delegating to save() gives us the id==appId upsert for free — no
        // more "draft_not_found" surprise when a legacy app opens the designer
        // for the first time.
        WorkflowEntity draft = save(appId, null, null, null, graph);
        if (features != null || environmentVariables != null || conversationVariables != null) {
            if (features != null) draft.setFeatures(features);
            if (environmentVariables != null) draft.setEnvironmentVariables(environmentVariables);
            if (conversationVariables != null) draft.setConversationVariables(conversationVariables);
            workflowMapper.updateById(draft);
        }
        return draft;
    }

    /**
     * Publish the current draft as a new immutable snapshot row and return it.
     * The draft row is left in place (id unchanged) so subsequent edits keep
     * hitting the same primary key. The caller is expected to update
     * {@code apps.workflow_id} to the returned snapshot's id.
     */
    @org.springframework.transaction.annotation.Transactional
    public WorkflowEntity publishDraft(String appId, String markedName, String markedComment) {
        WorkflowEntity draft = findDraft(appId);
        if (draft == null) {
            throw new AgentException("draft_not_found",
                    "No draft workflow for app " + appId + " — nothing to publish", null);
        }
        WorkflowEntity snapshot = new WorkflowEntity();
        // Deliberately do NOT set id — MyBatis-Plus mints a fresh one.
        snapshot.setAppId(appId);
        snapshot.setTenantId(draft.getTenantId());
        snapshot.setName(draft.getName());
        snapshot.setMode(draft.getMode());
        snapshot.setGraph(draft.getGraph());
        snapshot.setFeatures(draft.getFeatures());
        snapshot.setEnvironmentVariables(draft.getEnvironmentVariables());
        snapshot.setConversationVariables(draft.getConversationVariables());
        snapshot.setOutput(draft.getOutput());
        snapshot.setPublished(Boolean.TRUE);
        // Version label: use a caller-provided marked_name if given, else an
        // ISO-8601 timestamp. Matches the legacy project's convention.
        snapshot.setVersion(markedName != null && !markedName.isBlank()
                ? markedName
                : java.time.LocalDateTime.now().toString());
        snapshot.setMarkedName(markedName);
        snapshot.setMarkedComment(markedComment);
        workflowMapper.insert(snapshot);
        return snapshot;
    }

    /** All workflow rows for one app (draft + snapshots), newest first. */
    public java.util.List<WorkflowEntity> listByApp(String appId) {
        return workflowMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkflowEntity>()
                .eq(WorkflowEntity::getAppId, appId)
                .orderByDesc(WorkflowEntity::getCreatedAt));
    }

    /** Recent runs of a workflow — powers the "run history" panel on the canvas. */
    public java.util.List<WorkflowRunEntity> runs(String workflowId, int limit) {
        return runMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkflowRunEntity>()
                .eq(WorkflowRunEntity::getWorkflowId, workflowId)
                .orderByDesc(WorkflowRunEntity::getCreatedAt)
                .last("limit " + Math.max(1, Math.min(200, limit))));
    }

    /** Load, execute and persist a run of a stored workflow. */
    public WorkflowRunResult run(String workflowId, Map<String, Object> inputs, String conversationId) {
        WorkflowEntity entity = require(workflowId);
        WorkflowGraph graph = graphOf(entity);
        WorkflowRunResult result = engine.run(graph, inputs, conversationId);
        persistRun(workflowId, conversationId, inputs, result);
        return result;
    }

    /** Run an ad-hoc graph without persisting a definition; the run is still recorded. */
    public WorkflowRunResult runGraph(WorkflowGraph graph, Map<String, Object> inputs, String conversationId) {
        return runGraph(graph, inputs, conversationId, null);
    }

    /** Opaque-JSON variant of {@link #runGraph(WorkflowGraph, Map, String)}. */
    public WorkflowRunResult runGraph(JsonNode graph, Map<String, Object> inputs, String conversationId) {
        return runGraph(materializeGraph(graph), inputs, conversationId, null);
    }

    /** Same as {@link #runGraph(WorkflowGraph, Map, String)} but pushes each step to a listener as it happens. */
    public WorkflowRunResult runGraph(WorkflowGraph graph, Map<String, Object> inputs, String conversationId,
                                      Consumer<StepRecord> stepListener) {
        WorkflowRunResult result = engine.run(graph, inputs, conversationId, stepListener);
        persistRun(null, conversationId, inputs, result);
        return result;
    }

    /** Opaque-JSON streaming variant. */
    public WorkflowRunResult runGraph(JsonNode graph, Map<String, Object> inputs, String conversationId,
                                      Consumer<StepRecord> stepListener) {
        return runGraph(materializeGraph(graph), inputs, conversationId, stepListener);
    }

    /**
     * Turn an opaque graph JSON (as produced by the visual designer) into a
     * typed {@link WorkflowGraph} the engine can execute. Uses
     * {@link JsonUtils#parse(String, Class)} so
     * {@link io.github.aigoodle.workflow.graph.NodeType}'s
     * {@code @JsonCreator} normalises designer aliases (CONDITION → IF_ELSE,
     * kebab / lower-case → SNAKE_CASE, …). Unknown node types throw a clear
     * {@code graph_parse_error} rather than a raw Jackson stack trace.
     */
    private WorkflowGraph materializeGraph(JsonNode graph) {
        if (graph == null || graph.isNull()) {
            throw new AgentException("graph_required",
                    "Field 'graph' is required for ad-hoc runs", null);
        }
        try {
            WorkflowGraph typed = JsonUtils.parse(graph.toString(), WorkflowGraph.class);
            typed.reindex();
            return typed;
        } catch (Exception ex) {
            throw new AgentException("graph_parse_error",
                    "Could not parse graph: " + ex.getMessage(), ex);
        }
    }

    /** Same as {@link #run(String, Map, String)} but streaming step events. */
    public WorkflowRunResult run(String workflowId, Map<String, Object> inputs, String conversationId,
                                 Consumer<StepRecord> stepListener) {
        return run(workflowId, inputs, conversationId, stepListener, null);
    }

    /**
     * Full-fidelity overload: threads an optional {@link
     * io.github.aigoodle.workflow.chat.ChatStreamSink} so LLM nodes can emit
     * per-token deltas back to the caller (SSE chat). Blocking callers pass
     * {@code null} and the engine falls back to {@code .call().content()}.
     */
    public WorkflowRunResult run(String workflowId, Map<String, Object> inputs, String conversationId,
                                 Consumer<StepRecord> stepListener,
                                 io.github.aigoodle.workflow.chat.ChatStreamSink chatSink) {
        WorkflowEntity entity = require(workflowId);
        WorkflowGraph graph = graphOf(entity);
        WorkflowRunResult result = engine.run(graph, inputs, conversationId, stepListener, chatSink);
        persistRun(workflowId, conversationId, inputs, result);
        return result;
    }

    private void persistRun(String workflowId, String conversationId, Map<String, Object> inputs,
                            WorkflowRunResult result) {
        try {
            WorkflowRunEntity run = new WorkflowRunEntity();
            run.setId(result.getRunId());
            run.setWorkflowId(workflowId);
            run.setConversationId(conversationId);
            run.setStatus(result.isSuccess() ? "SUCCESS" : "FAILED");
            run.setInputsJson(JsonUtils.toJson(inputs));
            run.setOutputsJson(JsonUtils.toJson(result.getOutputs()));
            run.setStepsJson(JsonUtils.toJson(result.getSteps()));
            run.setError(result.getError());
            runMapper.insert(run);
        } catch (Exception ignored) {
            // observability persistence must never break a run
        }
    }

    /** Serialise a typed {@link WorkflowGraph} into a Jackson tree for storage. */
    private static JsonNode toJsonNode(WorkflowGraph graph) {
        return JsonUtils.mapper().valueToTree(graph);
    }

    private static JsonNode emptyGraphNode() {
        try {
            return JsonUtils.mapper().readTree(EMPTY_GRAPH_JSON);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to build empty graph seed", ex);
        }
    }
}
