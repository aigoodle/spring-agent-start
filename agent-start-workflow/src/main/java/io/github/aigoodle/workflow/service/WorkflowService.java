package io.github.aigoodle.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.workflow.chat.ChatStreamSink;
import io.github.aigoodle.workflow.engine.WorkflowEngine;
import io.github.aigoodle.workflow.engine.WorkflowRunResult;
import io.github.aigoodle.workflow.entity.WorkflowEntity;
import io.github.aigoodle.workflow.entity.WorkflowRunEntity;
import io.github.aigoodle.workflow.graph.WorkflowGraph;
import io.github.aigoodle.workflow.mapper.WorkflowMapper;
import io.github.aigoodle.workflow.mapper.WorkflowRunMapper;
import io.github.aigoodle.workflow.node.StepRecord;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Application service for workflow definitions, draft publication and execution.
 * Runtime details and observability persistence are delegated to focused collaborators.
 */
public class WorkflowService {

    private static final String DEFAULT_TENANT = "default";

    private final WorkflowMapper workflowMapper;
    private final WorkflowEngine workflowEngine;
    private final WorkflowGraphCodec graphCodec;
    private final WorkflowRunStore runStore;

    public WorkflowService(WorkflowMapper workflowMapper, WorkflowRunMapper runMapper,
                           WorkflowEngine workflowEngine) {
        this.workflowMapper = workflowMapper;
        this.workflowEngine = workflowEngine;
        this.graphCodec = new WorkflowGraphCodec();
        this.runStore = new WorkflowRunStore(runMapper);
    }

    public WorkflowEntity save(String appId, String tenantId, String name, String mode,
                               WorkflowGraph graph) {
        return save(appId, tenantId, name, mode, graph == null ? null : graphCodec.write(graph));
    }

    /** Creates or updates the single draft row owned by an application. */
    @Transactional
    public WorkflowEntity save(String appId, String tenantId, String name, String mode,
                               JsonNode graphDefinition) {
        return save(new WorkflowDraftDefinition(appId, tenantId, name, mode, graphDefinition));
    }

    /** Creates or updates the single draft row owned by an application. */
    @Transactional
    public WorkflowEntity save(WorkflowDraftDefinition definition) {
        requireAppId(definition.applicationId());
        WorkflowEntity draft = workflowMapper.selectById(definition.applicationId());
        if (draft == null) {
            return insertDraft(definition);
        }

        WorkflowEntityFactory.updateDefinition(
                draft, definition.name(), definition.mode(), definition.graph());
        workflowMapper.updateById(draft);
        return draft;
    }

    public WorkflowEntity require(String workflowId) {
        WorkflowEntity workflow = workflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new AgentException(
                    "workflow_not_found", "Workflow not found: " + workflowId, null);
        }
        return workflow;
    }

    public WorkflowGraph graphOf(WorkflowEntity workflow) {
        return graphCodec.read(workflow.getGraph());
    }

    public List<WorkflowEntity> list(String tenantId) {
        return workflowMapper.selectList(new LambdaQueryWrapper<WorkflowEntity>()
                .eq(WorkflowEntity::getTenantId, defaultIfBlank(tenantId, DEFAULT_TENANT))
                .orderByDesc(WorkflowEntity::getUpdatedAt));
    }

    public WorkflowEntity update(String workflowId, String name, String mode, WorkflowGraph graph) {
        return update(workflowId, name, mode, graph == null ? null : graphCodec.write(graph));
    }

    public WorkflowEntity update(String workflowId, String name, String mode, JsonNode graphDefinition) {
        WorkflowEntity workflow = require(workflowId);
        WorkflowEntityFactory.updateDefinition(workflow, name, mode, graphDefinition);
        workflowMapper.updateById(workflow);
        return workflow;
    }

    public void delete(String workflowId) {
        workflowMapper.deleteById(workflowId);
    }

    @Transactional
    public WorkflowEntity createDraft(String appId, String tenantId, String mode, String name,
                                      WorkflowGraph seedGraph) {
        JsonNode initialGraph = seedGraph == null ? graphCodec.emptyGraph() : graphCodec.write(seedGraph);
        return createDraft(new WorkflowDraftDefinition(appId, tenantId, name, mode, initialGraph));
    }

    @Transactional
    public WorkflowEntity createDraft(WorkflowDraftDefinition definition) {
        WorkflowEntity existingDraft = workflowMapper.selectById(definition.applicationId());
        if (existingDraft != null) {
            return existingDraft;
        }
        JsonNode initialGraph = definition.graph() == null ? graphCodec.emptyGraph() : definition.graph();
        return insertDraft(new WorkflowDraftDefinition(
                definition.applicationId(),
                defaultIfBlank(definition.tenantId(), DEFAULT_TENANT),
                definition.name(),
                defaultIfBlank(definition.mode(), WorkflowEntityFactory.DEFAULT_MODE),
                initialGraph));
    }

    public WorkflowEntity findDraft(String appId) {
        WorkflowEntity workflow = workflowMapper.selectById(appId);
        return workflow != null && WorkflowEntityFactory.DRAFT_VERSION.equals(workflow.getVersion())
                ? workflow
                : null;
    }

    @Transactional
    public WorkflowEntity saveDraft(String appId, WorkflowGraph graph, String features,
                                    String environmentVariables, String conversationVariables) {
        return saveDraft(
                appId,
                graph == null ? null : graphCodec.write(graph),
                features,
                environmentVariables,
                conversationVariables);
    }

    @Transactional
    public WorkflowEntity saveDraft(String appId, JsonNode graphDefinition, String features,
                                    String environmentVariables, String conversationVariables) {
        return saveDraft(appId, new WorkflowDraftChanges(
                graphDefinition, features, environmentVariables, conversationVariables));
    }

    @Transactional
    public WorkflowEntity saveDraft(String appId, WorkflowDraftChanges changes) {
        requireAppId(appId);
        WorkflowEntity draft = workflowMapper.selectById(appId);
        boolean isNewDraft = draft == null;
        if (isNewDraft) {
            draft = WorkflowEntityFactory.draft(new WorkflowDraftDefinition(
                    appId, null, null, null, changes.graph()));
        }
        WorkflowEntityFactory.updateDesignerState(draft, changes);

        if (isNewDraft) {
            workflowMapper.insert(draft);
        } else {
            workflowMapper.updateById(draft);
        }
        return draft;
    }

    /** Copies the mutable draft into a new, immutable published snapshot. */
    @Transactional
    public WorkflowEntity publishDraft(String appId, String markedName, String markedComment) {
        return publishDraft(appId, new WorkflowPublication(markedName, markedComment));
    }

    /** Copies the mutable draft into a new, immutable published snapshot. */
    @Transactional
    public WorkflowEntity publishDraft(String appId, WorkflowPublication publication) {
        WorkflowEntity draft = findDraft(appId);
        if (draft == null) {
            throw new AgentException(
                    "draft_not_found", "No draft workflow for app " + appId + "; nothing to publish", null);
        }

        WorkflowEntity snapshot = WorkflowEntityFactory.publishedSnapshot(
                draft, publication, LocalDateTime.now().toString());
        workflowMapper.insert(snapshot);
        return snapshot;
    }

    public List<WorkflowEntity> listByApp(String appId) {
        return workflowMapper.selectList(new LambdaQueryWrapper<WorkflowEntity>()
                .eq(WorkflowEntity::getAppId, appId)
                .orderByDesc(WorkflowEntity::getCreatedAt));
    }

    public List<WorkflowRunEntity> runs(String workflowId, int limit) {
        return runStore.findRecent(workflowId, limit);
    }

    public WorkflowRunResult run(String workflowId, Map<String, Object> inputs,
                                 String conversationId) {
        return executeStored(workflowId, inputs, conversationId, null, null);
    }

    public WorkflowRunResult run(String workflowId, Map<String, Object> inputs,
                                 String conversationId, Consumer<StepRecord> stepListener) {
        return executeStored(workflowId, inputs, conversationId, stepListener, null);
    }

    public WorkflowRunResult run(String workflowId, Map<String, Object> inputs,
                                 String conversationId, Consumer<StepRecord> stepListener,
                                 ChatStreamSink chatSink) {
        return executeStored(workflowId, inputs, conversationId, stepListener, chatSink);
    }

    public WorkflowRunResult runGraph(WorkflowGraph graph, Map<String, Object> inputs,
                                      String conversationId) {
        return executeAdHoc(graph, inputs, conversationId, null);
    }

    public WorkflowRunResult runGraph(JsonNode graphDefinition, Map<String, Object> inputs,
                                      String conversationId) {
        return executeAdHoc(graphCodec.read(graphDefinition), inputs, conversationId, null);
    }

    public WorkflowRunResult runGraph(WorkflowGraph graph, Map<String, Object> inputs,
                                      String conversationId, Consumer<StepRecord> stepListener) {
        return executeAdHoc(graph, inputs, conversationId, stepListener);
    }

    public WorkflowRunResult runGraph(JsonNode graphDefinition, Map<String, Object> inputs,
                                      String conversationId, Consumer<StepRecord> stepListener) {
        return executeAdHoc(graphCodec.read(graphDefinition), inputs, conversationId, stepListener);
    }

    private WorkflowRunResult executeStored(String workflowId, Map<String, Object> inputs,
                                            String conversationId, Consumer<StepRecord> stepListener,
                                            ChatStreamSink chatSink) {
        WorkflowGraph graph = graphOf(require(workflowId));
        WorkflowRunResult result = workflowEngine.run(
                graph, inputs, conversationId, stepListener, chatSink);
        runStore.recordStoredRun(workflowId, conversationId, inputs, result);
        return result;
    }

    private WorkflowRunResult executeAdHoc(WorkflowGraph graph, Map<String, Object> inputs,
                                           String conversationId, Consumer<StepRecord> stepListener) {
        WorkflowRunResult result = workflowEngine.run(graph, inputs, conversationId, stepListener);
        runStore.recordAdHocRun(conversationId, inputs, result);
        return result;
    }

    private WorkflowEntity insertDraft(WorkflowDraftDefinition definition) {
        WorkflowEntity draft = WorkflowEntityFactory.draft(definition);
        workflowMapper.insert(draft);
        return draft;
    }

    private static void requireAppId(String appId) {
        if (!hasText(appId)) {
            throw new AgentException(
                    "app_id_required", "appId is required for every workflow draft", null);
        }
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return hasText(value) ? value : defaultValue;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
