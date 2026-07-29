package io.github.aigoodle.agent.service;

import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.agent.entity.AppModelConfig;
import io.github.aigoodle.agent.mapper.AgentMapper;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.knowledge.entity.DatasetEntity;
import io.github.aigoodle.knowledge.service.DatasetService;
import lombok.Builder;
import lombok.Data;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Knowledge-base attachment for {@link AgentEntity}. The list of attached
 * dataset ids lives on the app's {@link AppModelConfig} sidecar (Dify parity)
 * because it's part of the "编排" drawer — no join table. This service
 * enforces cross-tenant isolation (an app can only attach datasets its own
 * tenant owns) and returns hydrated view rows so the console can render chips
 * without a second round trip.
 * <p>
 * Wired only when the knowledge module is on the classpath.
 */
public class AppDatasetService {

    private final AgentMapper agentMapper;
    private final AppModelConfigService modelConfigService;
    private final DatasetService datasetService;

    public AppDatasetService(AgentMapper agentMapper, AppModelConfigService modelConfigService,
                             DatasetService datasetService) {
        this.agentMapper = agentMapper;
        this.modelConfigService = modelConfigService;
        this.datasetService = datasetService;
    }

    /** Hydrated list — [{id, name, description}] — for the drawer chips. */
    public List<AttachedDataset> list(String appId) {
        requireApp(appId);
        List<String> ids = attachedIds(appId);
        List<AttachedDataset> out = new ArrayList<>();
        for (String id : ids) {
            DatasetEntity ds = datasetService.get(id);
            if (ds == null) continue;
            out.add(AttachedDataset.builder()
                    .id(ds.getId())
                    .name(ds.getName())
                    .description(ds.getDescription())
                    .indexingTechnique(ds.getIndexingTechnique() == null ? null : ds.getIndexingTechnique().name())
                    .documentCount(ds.getDocumentCount())
                    .build());
        }
        return out;
    }

    /**
     * Idempotent attach — deduplicates against the current list and rejects
     * ids from other tenants so the UI surfaces the mistake instead of
     * silently dropping.
     */
    @Transactional
    public List<AttachedDataset> attach(String appId, List<String> datasetIds) {
        AgentEntity app = requireApp(appId);
        Set<String> current = new LinkedHashSet<>(attachedIds(appId));
        for (String id : datasetIds) {
            if (id == null || id.isBlank()) continue;
            DatasetEntity ds = datasetService.get(id);
            if (ds == null) {
                throw new AgentException("dataset_not_found", "Dataset not found: " + id, null);
            }
            if (!java.util.Objects.equals(nz(ds.getTenantId()), nz(app.getTenantId()))) {
                throw new AgentException("dataset_cross_tenant",
                        "Dataset " + id + " belongs to a different tenant", null);
            }
            current.add(id);
        }
        persistIds(app, new ArrayList<>(current));
        return list(appId);
    }

    /** Remove one dataset id from the attached list. Silent no-op if not present. */
    @Transactional
    public List<AttachedDataset> detach(String appId, String datasetId) {
        AgentEntity app = requireApp(appId);
        List<String> current = new ArrayList<>(attachedIds(appId));
        current.removeIf(id -> id != null && id.equals(datasetId));
        persistIds(app, current);
        return list(appId);
    }

    /** Replace the whole list in one call — used by the "save" button on the settings panel. */
    @Transactional
    public List<AttachedDataset> replace(String appId, List<String> datasetIds) {
        AgentEntity app = requireApp(appId);
        Set<String> deduped = new LinkedHashSet<>();
        for (String id : datasetIds == null ? List.<String>of() : datasetIds) {
            if (id == null || id.isBlank()) continue;
            DatasetEntity ds = datasetService.get(id);
            if (ds == null) {
                throw new AgentException("dataset_not_found", "Dataset not found: " + id, null);
            }
            if (!java.util.Objects.equals(nz(ds.getTenantId()), nz(app.getTenantId()))) {
                throw new AgentException("dataset_cross_tenant",
                        "Dataset " + id + " belongs to a different tenant", null);
            }
            deduped.add(id);
        }
        persistIds(app, new ArrayList<>(deduped));
        return list(appId);
    }

    private List<String> attachedIds(String appId) {
        AppModelConfig cfg = modelConfigService.findByAppId(appId);
        return cfg == null ? List.of() : JsonUtils.parseList(cfg.getDatasetIdsJson(), String.class);
    }

    private void persistIds(AgentEntity app, List<String> ids) {
        AppModelConfig patch = new AppModelConfig();
        patch.setDatasetIdsJson(JsonUtils.toJson(ids));
        modelConfigService.upsert(app.getId(), app.getTenantId(), patch);
    }

    private AgentEntity requireApp(String appId) {
        AgentEntity app = agentMapper.selectById(appId);
        if (app == null) {
            throw new AgentException("agent_not_found", "Agent not found: " + appId, null);
        }
        return app;
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "default" : s;
    }

    /** View row returned to the console — flatter than {@link DatasetEntity}. */
    @Data
    @Builder
    public static class AttachedDataset {
        private String id;
        private String name;
        private String description;
        private String indexingTechnique;
        private Integer documentCount;
    }
}
