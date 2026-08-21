package io.github.aigoodle.web.controller;

import io.github.aigoodle.agent.entity.AgentVersionEntity;
import io.github.aigoodle.agent.api.AgentDefinition;
import io.github.aigoodle.agent.service.AgentVersionService;
import io.github.aigoodle.connector.channel.ChannelAuditService;
import io.github.aigoodle.web.common.ApiResponse;
import io.github.aigoodle.web.support.ChannelAdministrationPolicy;
import io.github.aigoodle.common.util.JsonUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

import static io.github.aigoodle.common.context.UserContextHolder.currentTenantId;
import static io.github.aigoodle.common.context.UserContextHolder.currentUserId;

/** Tenant-scoped publication lifecycle for immutable Agent versions. */
@RestController
@ConditionalOnBean(AgentVersionService.class)
@RequestMapping({"/apps/{appId}/versions", "/agents/{appId}/versions"})
public class AgentVersionController {
    public record View(String id, String tenantId, String appId, Integer versionNumber, String status,
                       String changeSummary, String publishedBy, LocalDateTime publishedAt,
                       String rollbackFromVersionId, String runtimeType, String runtimeRef) {
        static View from(AgentVersionEntity value) {
            AgentDefinition definition = JsonUtils.parse(value.getDefinitionJson(), AgentDefinition.class);
            return new View(value.getId(), value.getTenantId(), value.getAppId(), value.getVersionNumber(),
                    value.getStatus(), value.getChangeSummary(), value.getPublishedBy(), value.getPublishedAt(),
                    value.getRollbackFromVersionId(), definition == null ? "NATIVE" : definition.getRuntimeType(),
                    definition == null ? null : definition.getRuntimeRef());
        }
    }
    private final AgentVersionService versions;
    private final ChannelAdministrationPolicy administration;
    private final ChannelAuditService audits;
    public AgentVersionController(AgentVersionService versions, ChannelAdministrationPolicy administration,
                                  ChannelAuditService audits) {
        this.versions = versions; this.administration = administration; this.audits = audits;
    }

    @GetMapping
    public ApiResponse<List<View>> list(@PathVariable String appId) {
        return ApiResponse.ok(versions.list(currentTenantId(), appId).stream().map(View::from).toList());
    }

    @PostMapping("/publish")
    @Transactional
    public ApiResponse<View> publish(@PathVariable String appId,
                                                   @RequestBody(required = false) Map<String, String> body) {
        administration.requireAdministrator();
        AgentVersionEntity published = versions.publish(currentTenantId(), appId, currentUserId(), value(body, "summary"));
        audits.success("AGENT_VERSION_PUBLISH", "AGENT", appId, Map.of(
                "versionId", published.getId(), "versionNumber", published.getVersionNumber()));
        return ApiResponse.ok(View.from(published));
    }

    @PostMapping("/{versionId}/rollback")
    @Transactional
    public ApiResponse<View> rollback(@PathVariable String appId, @PathVariable String versionId,
                                                    @RequestBody(required = false) Map<String, String> body) {
        administration.requireAdministrator();
        AgentVersionEntity rolledBack = versions.rollback(currentTenantId(), appId, versionId,
                currentUserId(), value(body, "summary"));
        audits.success("AGENT_VERSION_ROLLBACK", "AGENT", appId, Map.of(
                "targetVersionId", versionId, "createdVersionId", rolledBack.getId(),
                "versionNumber", rolledBack.getVersionNumber()));
        return ApiResponse.ok(View.from(rolledBack));
    }

    @PostMapping("/{versionId}/disable")
    @Transactional
    public ApiResponse<View> disable(@PathVariable String appId, @PathVariable String versionId) {
        administration.requireAdministrator();
        AgentVersionEntity disabled = versions.disable(currentTenantId(), appId, versionId);
        audits.success("AGENT_VERSION_DISABLE", "AGENT", appId, Map.of(
                "versionId", versionId, "versionNumber", disabled.getVersionNumber()));
        return ApiResponse.ok(View.from(disabled));
    }

    private static String value(Map<String, String> body, String key) { return body == null ? null : body.get(key); }
}
