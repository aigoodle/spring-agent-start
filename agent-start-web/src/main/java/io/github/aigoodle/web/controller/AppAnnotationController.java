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

/** REST endpoints for application-owned answer annotations. */
@RestController
@ConditionalOnBean(AppAnnotationService.class)
@RequestMapping("${spring-agent.web.base-path:}/apps/{appId}/annotations")
public class AppAnnotationController {

    private final AppAnnotationService annotationService;

    public AppAnnotationController(AppAnnotationService annotationService) {
        this.annotationService = annotationService;
    }

    @GetMapping
    public ApiResponse<List<AppAnnotationEntity>> list(@PathVariable String appId) {
        return ApiResponse.ok(annotationService.list(appId));
    }

    @PostMapping
    public ApiResponse<AppAnnotationEntity> create(@PathVariable String appId,
                                                   @RequestBody AppAnnotationEntity annotation) {
        annotation.setAppId(appId);
        annotation.setId(null);
        return ApiResponse.ok(annotationService.create(annotation));
    }

    @PutMapping("/{id}")
    public ApiResponse<AppAnnotationEntity> update(@PathVariable String appId,
                                                   @PathVariable String id,
                                                   @RequestBody AppAnnotationEntity updates) {
        return ApiResponse.ok(annotationService.update(appId, id, updates));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String appId, @PathVariable String id) {
        annotationService.delete(appId, id);
        return ApiResponse.ok();
    }

    /** Records a manually triggered annotation hit. */
    @PostMapping("/{id}/hit")
    public ApiResponse<Void> hit(@PathVariable String appId, @PathVariable String id) {
        annotationService.recordHit(appId, id);
        return ApiResponse.ok();
    }
}
