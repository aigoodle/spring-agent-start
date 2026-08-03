package io.github.aigoodle.web.service;

import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.agent.service.AgentService;
import io.github.aigoodle.agent.service.CreateAgentRequest;
import io.github.aigoodle.workflow.service.WorkflowDraftDefinition;
import io.github.aigoodle.workflow.service.WorkflowService;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

/** Coordinates application catalog persistence with optional workflow draft creation. */
public final class AgentApplicationCoordinator {

    private final AgentService agentService;
    private final ObjectProvider<WorkflowService> workflowServiceProvider;

    public AgentApplicationCoordinator(AgentService agentService,
                                       ObjectProvider<WorkflowService> workflowServiceProvider) {
        this.agentService = agentService;
        this.workflowServiceProvider = workflowServiceProvider;
    }

    public List<AgentEntity> list(String tenantId) {
        List<AgentEntity> applications = agentService.list(tenantId);
        applications.forEach(agentService::enrich);
        return applications;
    }

    public AgentEntity get(String appId) {
        return agentService.enrich(agentService.require(appId));
    }

    public AgentEntity create(CreateAgentRequest request) {
        AgentEntity application = agentService.create(request);
        createWorkflowDraftIfNeeded(application);
        return agentService.enrich(application);
    }

    public AgentEntity update(String appId, CreateAgentRequest request) {
        return agentService.enrich(agentService.update(appId, request));
    }

    private void createWorkflowDraftIfNeeded(AgentEntity application) {
        if (!isFlowMode(application.getMode())) {
            return;
        }
        WorkflowService workflowService = workflowServiceProvider.getIfAvailable();
        if (workflowService == null) {
            return;
        }
        workflowService.createDraft(new WorkflowDraftDefinition(
                application.getId(),
                application.getTenantId(),
                application.getName(),
                application.getMode(),
                null));
        agentService.bindWorkflowId(application.getId(), application.getId());
    }

    private static boolean isFlowMode(String mode) {
        return "workflow".equals(mode) || "chatflow".equals(mode);
    }
}
