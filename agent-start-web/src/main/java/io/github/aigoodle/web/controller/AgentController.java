package io.github.aigoodle.web.controller;

import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.agent.entity.AppModelConfig;
import io.github.aigoodle.agent.service.AgentService;
import io.github.aigoodle.agent.service.CreateAgentRequest;
import io.github.aigoodle.tool.ToolRegistry;
import io.github.aigoodle.web.common.ApiResponse;
import io.github.aigoodle.web.service.AgentApplicationCoordinator;
import io.github.aigoodle.web.support.AgentToolViewMapper;
import io.github.aigoodle.workflow.service.WorkflowService;
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

import java.util.List;
import java.util.Map;

/** Agent application metadata, configuration and tool-discovery endpoints. */
@RestController
@ConditionalOnBean(AgentService.class)
@RequestMapping("${spring-agent.web.base-path:}/agents")
public class AgentController {

    private final AgentService agentService;
    private final AgentApplicationCoordinator applicationCoordinator;
    private final AgentToolViewMapper toolViewMapper;

    public AgentController(AgentService agentService,
                           ObjectProvider<ToolRegistry> toolRegistryProvider,
                           ObjectProvider<WorkflowService> workflowServiceProvider) {
        this.agentService = agentService;
        this.applicationCoordinator = new AgentApplicationCoordinator(
                agentService, workflowServiceProvider);
        this.toolViewMapper = new AgentToolViewMapper(agentService, toolRegistryProvider);
    }

    @GetMapping
    public ApiResponse<List<AgentEntity>> list(@RequestParam(required = false) String tenantId) {
        return ApiResponse.ok(applicationCoordinator.list(tenantId));
    }

    @GetMapping("/{id}")
    public ApiResponse<AgentEntity> get(@PathVariable String id) {
        return ApiResponse.ok(applicationCoordinator.get(id));
    }

    @GetMapping("/{id}/model-config")
    public ApiResponse<AppModelConfig> modelConfig(@PathVariable String id) {
        agentService.require(id);
        return ApiResponse.ok(agentService.getModelConfig(id));
    }

    @PostMapping
    public ApiResponse<AgentEntity> create(@RequestBody CreateAgentRequest request) {
        return ApiResponse.ok(applicationCoordinator.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<AgentEntity> update(@PathVariable String id,
                                           @RequestBody CreateAgentRequest request) {
        return ApiResponse.ok(applicationCoordinator.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        agentService.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/tools")
    public ApiResponse<List<Map<String, Object>>> tools(@PathVariable String id) {
        return ApiResponse.ok(toolViewMapper.toolsOf(id));
    }
}
