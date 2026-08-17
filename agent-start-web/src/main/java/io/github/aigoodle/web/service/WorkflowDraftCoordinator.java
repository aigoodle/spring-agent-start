package io.github.aigoodle.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aigoodle.agent.entity.AppEntity;
import io.github.aigoodle.agent.service.AppService;
import io.github.aigoodle.workflow.entity.WorkflowEntity;
import io.github.aigoodle.workflow.service.WorkflowDraftChanges;
import io.github.aigoodle.workflow.service.WorkflowDraftDefinition;
import io.github.aigoodle.workflow.service.WorkflowPublication;
import io.github.aigoodle.workflow.service.WorkflowService;
import org.springframework.beans.factory.ObjectProvider;

/** Coordinates an application's mutable draft with its published workflow pointer. */
public final class WorkflowDraftCoordinator {

    private final WorkflowService workflowService;
    private final ObjectProvider<AppService> appServiceProvider;

    public WorkflowDraftCoordinator(WorkflowService workflowService,
                                    ObjectProvider<AppService> appServiceProvider) {
        this.workflowService = workflowService;
        this.appServiceProvider = appServiceProvider;
    }

    /** Returns the existing draft or creates it for a legacy workflow-mode application. */
    public WorkflowEntity findOrCreate(String appId) {
        WorkflowEntity draft = workflowService.findDraft(appId);
        if (draft != null) {
            return draft;
        }

        AppService appService = appServiceProvider.getIfAvailable();
        AppEntity application = findFlowApplication(appService, appId);
        if (application == null) {
            return null;
        }

        WorkflowEntity createdDraft = workflowService.createDraft(new WorkflowDraftDefinition(
                application.getId(),
                application.getTenantId(),
                application.getName(),
                application.getMode(),
                null));
        bindWorkflowQuietly(appService, application.getId(), createdDraft.getId());
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
        bindWorkflowQuietly(appServiceProvider.getIfAvailable(), appId, snapshot.getId());
        return snapshot;
    }

    private static AppEntity findFlowApplication(AppService appService, String appId) {
        if (appService == null) {
            return null;
        }
        try {
            AppEntity application = appService.require(appId);
            return isFlowMode(application.getMode()) ? application : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void bindWorkflowQuietly(AppService appService, String appId, String workflowId) {
        if (appService == null) {
            return;
        }
        try {
            appService.bindWorkflowId(appId, workflowId);
        } catch (Exception ignored) {
            // The workflow is durable even if its application disappeared during the operation.
        }
    }

    private static boolean isFlowMode(String mode) {
        return "workflow".equals(mode) || "chatflow".equals(mode);
    }
}
