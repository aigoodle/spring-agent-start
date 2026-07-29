package io.github.aigoodle.web.controller;

import io.github.aigoodle.agent.entity.AppAnnotationEntity;
import io.github.aigoodle.agent.service.AppAnnotationService;
import io.github.aigoodle.web.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST facade over {@link AppAnnotationService} — the "标注" tab in the app
 * design drawer talks to these endpoints. Kept as a separate controller from
 * {@link AgentController} because the annotation feature is optional and can
 * be composed in / out independently.
 */
@RestController
@ConditionalOnBean(AppAnnotationService.class)
@RequestMapping("${spring-agent.web.base-path:}/apps/{appId}/annotations")
public class AppAnnotationController {

    private final AppAnnotationService service;

    public AppAnnotationController(AppAnnotationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<AppAnnotationEntity>> list(@PathVariable String appId) {
        return ApiResponse.ok(service.list(appId));
    }

    @PostMapping
    public ApiResponse<AppAnnotationEntity> create(@PathVariable String appId,
                                                   @RequestBody AppAnnotationEntity body) {
        // Trust the URL over the body — the app is scoped by the route.
        body.setAppId(appId);
        body.setId(null);
        return ApiResponse.ok(service.create(body));
    }

    @PutMapping("/{id}")
    public ApiResponse<AppAnnotationEntity> update(@PathVariable String appId,
                                                   @PathVariable String id,
                                                   @RequestBody AppAnnotationEntity patch) {
        return ApiResponse.ok(service.update(id, patch));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String appId, @PathVariable String id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    /** Manual +1 endpoint — useful for the debug drawer to simulate a hit. */
    @PostMapping("/{id}/hit")
    public ApiResponse<Void> hit(@PathVariable String appId, @PathVariable String id) {
        service.bumpHit(id);
        return ApiResponse.ok();
    }
}
