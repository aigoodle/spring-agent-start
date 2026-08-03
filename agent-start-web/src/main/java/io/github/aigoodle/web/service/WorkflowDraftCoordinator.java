package io.github.aigoodle.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.agent.service.AgentService;
import io.github.aigoodle.workflow.entity.WorkflowEntity;
import io.github.aigoodle.workflow.service.WorkflowDraftChanges;
import io.github.aigoodle.workflow.service.WorkflowDraftDefinition;
import io.github.aigoodle.workflow.service.WorkflowPublication;
import io.github.aigoodle.workflow.service.WorkflowService;
import org.springframework.beans.factory.ObjectProvider;

/** Coordinates an application's mutable draft with its published workflow pointer. */
public final class WorkflowDraftCoordinator {

    private final WorkflowService workflowService;
    private final ObjectProvider<AgentService> agentServiceProvider;

    public WorkflowDraftCoordinator(WorkflowService workflowService,
                                    ObjectProvider<AgentService> agentServiceProvider) {
        this.workflowService = workflowService;
        this.agentServiceProvider = agentServiceProvider;
    }

    /** Returns the existing draft or creates it for a legacy workflow-mode application. */
    public WorkflowEntity findOrCreate(String appId) {
        WorkflowEntity draft = workflowService.findDraft(appId);
        if (draft != null) {
            return draft;
        }

        AgentService agentService = agentServiceProvider.getIfAvailable();
        AgentEntity application = findFlowApplication(agentService, appId);
        if (application == null) {
            return null;
        }

        WorkflowEntity createdDraft = workflowService.createDraft(new WorkflowDraftDefinition(
                application.getId(),
                application.getTenantId(),
                application.getName(),
                application.getMode(),
                null));
        bindWorkflowQuietly(agentService, application.getId(), createdDraft.getId());
        return createdDraft;
    }

    public WorkflowEntity save(String appId, JsonNode graph) {
        findOrCreate(appId);
        return workflowService.saveDraft(appId, WorkflowDraftChanges.graphOnly(graph));
    }

    public WorkflowEntity publish(String appId, String markedName, String markedComment) {
        findOrCreate(appId);
        WorkflowEntity snapshot = workflowService.publishDraft(
                appId, new WorkflowPublication(markedName, markedComment));
        bindWorkflowQuietly(agentServiceProvider.getIfAvailable(), appId, snapshot.getId());
        return snapshot;
    }

    private static AgentEntity findFlowApplication(AgentService agentService, String appId) {
        if (agentService == null) {
            return null;
        }
        try {
            AgentEntity application = agentService.require(appId);
            return isFlowMode(application.getMode()) ? application : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void bindWorkflowQuietly(AgentService agentService, String appId, String workflowId) {
        if (agentService == null) {
            return;
        }
        try {
            agentService.bindWorkflowId(appId, workflowId);
        } catch (Exception ignored) {
            // The workflow is durable even if its application disappeared during the operation.
        }
    }

    private static boolean isFlowMode(String mode) {
        return "workflow".equals(mode) || "chatflow".equals(mode);
    }
}
