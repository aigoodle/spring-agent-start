package io.github.aigoodle.agent.service;

import io.github.aigoodle.agent.entity.AppEntity;
import io.github.aigoodle.agent.entity.AppModelConfigEntity;

import java.util.List;

/**
 * Application-catalog boundary. Agent execution remains in {@link AgentService};
 * callers that only manage or resolve applications should depend on this type.
 */
public class AppService {

    private final AgentService agentRuntime;

    public AppService(AgentService agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    public AppEntity create(SaveAppRequest request) {
        return agentRuntime.create(request);
    }

    public AppEntity update(String appId, SaveAppRequest request) {
        return agentRuntime.update(appId, request);
    }

    public void delete(String appId) {
        agentRuntime.delete(appId);
    }

    public AppEntity require(String appId) {
        return agentRuntime.require(appId);
    }

    public List<AppEntity> list(String tenantId) {
        return agentRuntime.list(tenantId);
    }

    public AppEntity enrich(AppEntity application) {
        return agentRuntime.enrich(application);
    }

    public AppEntity requireVisibleByCode(String appCode, String tenantId, String rootTenantId) {
        return agentRuntime.requireVisibleByCode(appCode, tenantId, rootTenantId);
    }

    public AppModelConfigEntity getModelConfig(String appId) {
        return agentRuntime.getModelConfig(appId);
    }

    public AppEntity bindWorkflowId(String appId, String workflowId) {
        return agentRuntime.bindWorkflowId(appId, workflowId);
    }
}
