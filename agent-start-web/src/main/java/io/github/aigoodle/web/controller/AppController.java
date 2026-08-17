package io.github.aigoodle.web.controller;

import io.github.aigoodle.agent.entity.AppEntity;
import io.github.aigoodle.agent.entity.AppModelConfigEntity;
import io.github.aigoodle.agent.service.AppService;
import io.github.aigoodle.agent.service.SaveAppRequest;
import io.github.aigoodle.tool.ToolRegistry;
import io.github.aigoodle.web.common.ApiResponse;
import io.github.aigoodle.web.service.AppLifecycleCoordinator;
import io.github.aigoodle.web.support.AppToolViewMapper;
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

import static io.github.aigoodle.common.context.UserContextHolder.currentTenantId;

/** Application metadata, configuration and tool-discovery endpoints. */
@RestController
@ConditionalOnBean(AppService.class)
@RequestMapping({"/apps", "/agents"})
public class AppController {

    private final AppService appService;
    private final AppLifecycleCoordinator applicationCoordinator;
    private final AppToolViewMapper toolViewMapper;

    public AppController(AppService appService,
                           ObjectProvider<ToolRegistry> toolRegistryProvider,
                           ObjectProvider<WorkflowService> workflowServiceProvider) {
        this.appService = appService;
        this.applicationCoordinator = new AppLifecycleCoordinator(
                appService, workflowServiceProvider);
        this.toolViewMapper = new AppToolViewMapper(appService, toolRegistryProvider);
    }

    @GetMapping
    public ApiResponse<List<AppEntity>> list() {
        return ApiResponse.ok(applicationCoordinator.list(currentTenantId()));
    }

    @GetMapping("/{id}")
    public ApiResponse<AppEntity> get(@PathVariable String id) {
        return ApiResponse.ok(applicationCoordinator.get(id));
    }

    @GetMapping("/{id}/model-config")
    public ApiResponse<AppModelConfigEntity> modelConfig(@PathVariable String id) {
        appService.require(id);
        return ApiResponse.ok(appService.getModelConfig(id));
    }

    @PostMapping
    public ApiResponse<AppEntity> create(@RequestBody SaveAppRequest request) {
        request.setTenantId(currentTenantId());
        return ApiResponse.ok(applicationCoordinator.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<AppEntity> update(@PathVariable String id,
                                           @RequestBody SaveAppRequest request) {
        request.setTenantId(currentTenantId());
        return ApiResponse.ok(applicationCoordinator.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        appService.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/tools")
    public ApiResponse<List<Map<String, Object>>> tools(@PathVariable String id) {
        return ApiResponse.ok(toolViewMapper.toolsOf(id));
    }
}
