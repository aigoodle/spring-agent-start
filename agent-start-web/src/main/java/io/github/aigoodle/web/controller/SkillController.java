package io.github.aigoodle.web.controller;

import io.github.aigoodle.skill.entity.SkillEntity;
import io.github.aigoodle.skill.service.SaveSkillRequest;
import io.github.aigoodle.skill.service.SkillService;
import io.github.aigoodle.web.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static io.github.aigoodle.common.context.UserContextHolder.currentTenantId;

@RestController
@ConditionalOnBean(SkillService.class)
@RequestMapping("/skills")
public class SkillController {
    private final SkillService service;
    public SkillController(SkillService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<SkillEntity>> list(@RequestParam(required = false) String status) {
        return ApiResponse.ok(service.list(currentTenantId(), status));
    }
    @GetMapping("/{id}")
    public ApiResponse<SkillEntity> get(@PathVariable String id) {
        return ApiResponse.ok(service.require(currentTenantId(), id));
    }
    @PostMapping
    public ApiResponse<SkillEntity> create(@RequestBody SaveSkillRequest request) {
        return ApiResponse.ok(service.create(currentTenantId(), request));
    }
    @PutMapping("/{id}")
    public ApiResponse<SkillEntity> update(@PathVariable String id, @RequestBody SaveSkillRequest request) {
        return ApiResponse.ok(service.update(currentTenantId(), id, request));
    }
    @PostMapping("/{id}/status")
    public ApiResponse<SkillEntity> status(@PathVariable String id, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(service.setStatus(currentTenantId(), id, body.get("status")));
    }
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        service.delete(currentTenantId(), id);
        return ApiResponse.ok();
    }
}
