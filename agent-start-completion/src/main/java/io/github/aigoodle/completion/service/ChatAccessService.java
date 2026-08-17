package io.github.aigoodle.completion.service;

import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.agent.service.AgentService;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.workflow.entity.WorkflowEntity;
import io.github.aigoodle.workflow.service.WorkflowService;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Objects;

/** Reusable resource-ownership checks for host-provided access policies. */
public class ChatAccessService {

    private final AgentService agentService;
    private final ObjectProvider<WorkflowService> workflowServices;
    private final String rootTenantId;

    public ChatAccessService(AgentService agentService,
                             ObjectProvider<WorkflowService> workflowServices) {
        this(agentService, workflowServices, "root");
    }

    public ChatAccessService(AgentService agentService,
                             ObjectProvider<WorkflowService> workflowServices,
                             String rootTenantId) {
        this.agentService = agentService;
        this.workflowServices = workflowServices;
        this.rootTenantId = rootTenantId == null || rootTenantId.isBlank() ? "root" : rootTenantId;
    }

    public AgentEntity requireVisibleAppByCode(String appCode, String executionTenantId) {
        return agentService.requireVisibleByCode(appCode, executionTenantId, rootTenantId);
    }

    public String requireOwnedApp(String appId, String tenantId) {
        AgentEntity app = agentService.require(appId);
        if (!Objects.equals(app.getTenantId(), tenantId)) {
            // Deliberately hide whether an app in another tenant exists.
            throw new AgentException("app_not_found", "应用不存在或无权访问", null);
        }
        return app.getId();
    }

    public String requireOwnedWorkflow(String appId, String tenantId, String workflowId) {
        String resolvedAppId = requireOwnedApp(appId, tenantId);
        if (workflowId == null || workflowId.isBlank()) {
            return resolvedAppId;
        }
        WorkflowService workflowService = workflowServices.getIfAvailable();
        if (workflowService == null) {
            throw new AgentException("workflow_unavailable", "工作流模块未启用", null);
        }
        WorkflowEntity workflow = workflowService.require(workflowId);
        if (!Objects.equals(resolvedAppId, workflow.getAppId())
                || !Objects.equals(workflow.getTenantId(), tenantId)) {
            throw new AgentException("workflow_not_found", "工作流不存在或不属于当前应用", null);
        }
        return resolvedAppId;
    }

    /** Enforces publication switches after an API key has resolved its app. */
    public String requireExternalApp(String appId) {
        AgentEntity app = agentService.require(appId);
        if (!Boolean.TRUE.equals(app.getEnableApi())
                || !Boolean.TRUE.equals(app.getPublished())
                || "disabled".equalsIgnoreCase(app.getStatus())) {
            throw new AgentException("app_api_unavailable", "应用 API 未启用或尚未发布", null);
        }
        return app.getId();
    }
}
