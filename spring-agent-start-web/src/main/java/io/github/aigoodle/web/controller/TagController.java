package io.github.aigoodle.web.controller;

import io.github.aigoodle.agent.entity.TagBindingEntity;
import io.github.aigoodle.agent.entity.TagEntity;
import io.github.aigoodle.agent.service.TagService;
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
 * Tenant-scoped organisational tags for apps + datasets (Dify parity — the
 * sidebar filter chips). One tag row + many bindings.
 */
@RestController
@ConditionalOnBean(TagService.class)
@RequestMapping("${spring-agent.web.base-path:}/tags")
public class TagController {

    private final TagService service;

    public TagController(TagService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<TagEntity>> list(@RequestParam(required = false) String tenantId,
                                             @RequestParam(required = false) String type) {
        return ApiResponse.ok(service.list(tenantId, type));
    }

    @PostMapping
    public ApiResponse<TagEntity> create(@RequestBody TagEntity body) {
        return ApiResponse.ok(service.create(body));
    }

    @PostMapping("/{id}/rename")
    public ApiResponse<TagEntity> rename(@PathVariable String id, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(service.rename(id, body.get("name")));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    /** List bindings for a specific target (app id or dataset id). */
    @GetMapping("/bindings")
    public ApiResponse<List<TagBindingEntity>> bindings(@RequestParam String targetId,
                                                        @RequestParam(required = false) String targetType) {
        return ApiResponse.ok(service.bindings(targetId, targetType));
    }

    @PostMapping("/bindings")
    public ApiResponse<Void> bind(@RequestBody Map<String, String> body) {
        service.bind(body.get("tagId"), body.get("targetId"), body.getOrDefault("targetType", "app"));
        return ApiResponse.ok();
    }

    @DeleteMapping("/bindings")
    public ApiResponse<Void> unbind(@RequestParam String tagId, @RequestParam String targetId) {
        service.unbind(tagId, targetId);
        return ApiResponse.ok();
    }
}
