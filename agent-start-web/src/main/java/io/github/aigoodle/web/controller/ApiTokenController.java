package io.github.aigoodle.web.controller;

import io.github.aigoodle.agent.entity.ApiTokenEntity;
import io.github.aigoodle.agent.service.ApiTokenService;
import io.github.aigoodle.web.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Manage the per-app API tokens shown on the "API 访问" tab. Values are minted
 * server-side and returned once — subsequent list/get calls show the full
 * value again because this is an internal console (matches Dify's UX).
 */
@RestController
@ConditionalOnBean(ApiTokenService.class)
@RequestMapping("${spring-agent.web.base-path:}/apps/{appId}/api-tokens")
public class ApiTokenController {

    private final ApiTokenService service;

    public ApiTokenController(ApiTokenService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ApiTokenEntity>> list(@PathVariable String appId) {
        return ApiResponse.ok(service.listByApp(appId));
    }

    @PostMapping
    public ApiResponse<ApiTokenEntity> create(@PathVariable String appId,
                                              @RequestParam(required = false) String tenantId,
                                              @RequestBody(required = false) Map<String, String> body) {
        String name = body == null ? null : body.get("name");
        String type = body == null ? null : body.get("type");
        return ApiResponse.ok(service.create(appId, tenantId, name, type));
    }

    @PostMapping("/{id}/rename")
    public ApiResponse<ApiTokenEntity> rename(@PathVariable String appId, @PathVariable String id,
                                              @RequestBody Map<String, String> body) {
        return ApiResponse.ok(service.rename(id, body.get("name")));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String appId, @PathVariable String id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
