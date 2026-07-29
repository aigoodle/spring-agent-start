package io.github.aigoodle.web.controller;

import io.github.aigoodle.agent.entity.ConversationEntity;
import io.github.aigoodle.agent.service.ConversationService;
import io.github.aigoodle.web.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Chat-session (conversation) CRUD for the console sidebar — list / rename /
 * pin / delete. The chat runtime itself does not go through this controller;
 * it drops rows into the same table via {@link ConversationService#ensure}.
 */
@RestController
@ConditionalOnBean(ConversationService.class)
@RequestMapping("${spring-agent.web.base-path:}/apps/{appId}/conversations")
public class ConversationController {

    private final ConversationService service;

    public ConversationController(ConversationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ConversationEntity>> list(@PathVariable String appId) {
        return ApiResponse.ok(service.listByApp(appId));
    }

    @GetMapping("/{id}")
    public ApiResponse<ConversationEntity> get(@PathVariable String appId, @PathVariable String id) {
        return ApiResponse.ok(service.require(id));
    }

    @PostMapping("/{id}/rename")
    public ApiResponse<ConversationEntity> rename(@PathVariable String appId, @PathVariable String id,
                                                   @RequestBody Map<String, String> body) {
        return ApiResponse.ok(service.rename(id, body.get("name")));
    }

    @PostMapping("/{id}/pin")
    public ApiResponse<ConversationEntity> pin(@PathVariable String appId, @PathVariable String id,
                                                @RequestBody Map<String, Boolean> body) {
        return ApiResponse.ok(service.togglePinned(id, Boolean.TRUE.equals(body.get("pinned"))));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String appId, @PathVariable String id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
