package io.github.aigoodle.model.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.model.entity.PredefinedModelEntity;
import io.github.aigoodle.model.entity.ProviderDefinitionEntity;
import io.github.aigoodle.model.enums.ModelFeature;
import io.github.aigoodle.model.enums.ModelType;
import io.github.aigoodle.model.mapper.PredefinedModelMapper;
import io.github.aigoodle.model.mapper.ProviderDefinitionMapper;
import io.github.aigoodle.model.provider.CredentialField;
import io.github.aigoodle.model.provider.ModelParameterRule;
import io.github.aigoodle.model.provider.PredefinedModel;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Dify-parity DB-backed provider metadata layer. Replaces the previous
 * "hard-coded Java bean is the source of truth" flow with:
 * <ul>
 *   <li>{@code agent_model_provider}   — provider definitions (label / icon /
 *       credential schema / parameter rules / etc). Java built-ins auto-seed
 *       {@code source='builtin'} rows on startup; external modules or admin UI
 *       can add {@code source='external'|'custom'} rows without touching Java
 *       code.</li>
 *   <li>{@code agent_predefined_model} — provider's shipped model catalog. Also
 *       seeded from Java on first boot; editable afterwards so adding a new
 *       model to an existing provider does not require redeploy.</li>
 * </ul>
 * The Java {@link io.github.aigoodle.model.provider.ModelProvider} bean
 * referenced by {@link ProviderDefinitionEntity#getImplementationKey()} still
 * supplies the wire code (build ChatModel / EmbeddingModel / list-remote) —
 * this service owns metadata only.
 */
public class ProviderDefinitionService {

    /** Sentinel tenant id for "global" definitions (visible to every tenant). */
    public static final String SYSTEM_TENANT = "system";

    private final ProviderDefinitionMapper providerMapper;
    private final PredefinedModelMapper predefinedMapper;

    public ProviderDefinitionService(ProviderDefinitionMapper providerMapper,
                                     PredefinedModelMapper predefinedMapper) {
        this.providerMapper = providerMapper;
        this.predefinedMapper = predefinedMapper;
    }

    // ---------------------------------------------------------- definitions

    /**
     * Return every provider definition visible to {@code tenantId}: global
     * ({@code tenant_id='system'}) rows AND any {@code tenant_id=tenantId} rows.
     * Sorted by {@code sortOrder} then name.
     */
    public List<ProviderDefinitionEntity> list(String tenantId) {
        LambdaQueryWrapper<ProviderDefinitionEntity> q =
                new LambdaQueryWrapper<ProviderDefinitionEntity>()
                        .eq(ProviderDefinitionEntity::getEnabled, Boolean.TRUE)
                        .in(ProviderDefinitionEntity::getTenantId, effectiveTenants(tenantId))
                        .orderByAsc(ProviderDefinitionEntity::getSortOrder)
                        .orderByAsc(ProviderDefinitionEntity::getName);
        return providerMapper.selectList(q);
    }

    /** Find a definition by provider {@code name}, respecting tenant scoping. */
    public ProviderDefinitionEntity findByName(String tenantId, String name) {
        if (name == null) return null;
        return providerMapper.selectOne(new LambdaQueryWrapper<ProviderDefinitionEntity>()
                .eq(ProviderDefinitionEntity::getName, name)
                .in(ProviderDefinitionEntity::getTenantId, effectiveTenants(tenantId))
                .last("limit 1"));
    }

    public ProviderDefinitionEntity requireByName(String tenantId, String name) {
        ProviderDefinitionEntity entity = findByName(tenantId, name);
        if (entity == null) {
            throw new AgentException("provider_not_found",
                    "No provider definition '" + name + "' for tenant '" + tenantId + "'", null);
        }
        return entity;
    }

    /**
     * Upsert a definition by (tenant_id, name). Existing rows with the same
     * key have their metadata refreshed <em>except</em> {@code enabled} and
     * {@code sortOrder} — these are user-owned and must not be reset by a
     * seed pass.
     */
    @Transactional
    public ProviderDefinitionEntity upsert(ProviderDefinitionEntity next) {
        if (next.getTenantId() == null) next.setTenantId(SYSTEM_TENANT);
        if (next.getSource() == null) next.setSource("custom");
        if (next.getEnabled() == null) next.setEnabled(Boolean.TRUE);
        if (next.getSortOrder() == null) next.setSortOrder(100);
        if (next.getSupportsRemoteModelListing() == null) {
            next.setSupportsRemoteModelListing(Boolean.FALSE);
        }
        ProviderDefinitionEntity existing = providerMapper.selectOne(
                new LambdaQueryWrapper<ProviderDefinitionEntity>()
                        .eq(ProviderDefinitionEntity::getTenantId, next.getTenantId())
                        .eq(ProviderDefinitionEntity::getName, next.getName())
                        .last("limit 1"));
        if (existing == null) {
            providerMapper.insert(next);
            return next;
        }
        // Refresh mutable metadata; keep user-owned enabled / sort_order.
        existing.setLabel(next.getLabel());
        existing.setDescription(next.getDescription());
        existing.setIcon(next.getIcon());
        existing.setSvgIcon(next.getSvgIcon());
        existing.setSupportedModelTypes(next.getSupportedModelTypes());
        existing.setCredentialSchema(next.getCredentialSchema());
        existing.setDefaultParameterRules(next.getDefaultParameterRules());
        existing.setImplementationKey(next.getImplementationKey());
        existing.setDefaultBaseUrl(next.getDefaultBaseUrl());
        existing.setSource(next.getSource());
        existing.setSupportsRemoteModelListing(next.getSupportsRemoteModelListing());
        providerMapper.updateById(existing);
        return existing;
    }

    @Transactional
    public void updatePartial(String id, Map<String, Object> patch) {
        ProviderDefinitionEntity entity = providerMapper.selectById(id);
        if (entity == null) {
            throw new AgentException("provider_not_found", "No provider definition with id " + id, null);
        }
        if (patch.containsKey("label"))         entity.setLabel(str(patch.get("label")));
        if (patch.containsKey("description"))   entity.setDescription(str(patch.get("description")));
        if (patch.containsKey("icon"))          entity.setIcon(str(patch.get("icon")));
        if (patch.containsKey("svgIcon"))       entity.setSvgIcon(str(patch.get("svgIcon")));
        if (patch.containsKey("defaultBaseUrl")) entity.setDefaultBaseUrl(str(patch.get("defaultBaseUrl")));
        if (patch.containsKey("sortOrder"))     entity.setSortOrder((Integer) patch.get("sortOrder"));
        if (patch.containsKey("enabled"))       entity.setEnabled(bool(patch.get("enabled")));
        if (patch.containsKey("supportsRemoteModelListing"))
            entity.setSupportsRemoteModelListing(bool(patch.get("supportsRemoteModelListing")));
        if (patch.containsKey("credentialSchema")) {
            entity.setCredentialSchema(JsonUtils.toJson(patch.get("credentialSchema")));
        }
        if (patch.containsKey("defaultParameterRules")) {
            entity.setDefaultParameterRules(JsonUtils.toJson(patch.get("defaultParameterRules")));
        }
        if (patch.containsKey("supportedModelTypes")) {
            entity.setSupportedModelTypes(JsonUtils.toJson(patch.get("supportedModelTypes")));
        }
        providerMapper.updateById(entity);
    }

    @Transactional
    public void delete(String id) {
        ProviderDefinitionEntity entity = providerMapper.selectById(id);
        if (entity == null) return;
        // Refuse to delete a builtin — deleting metadata would leave the Java bean
        // dangling. Callers should disable it via `enabled=false` instead.
        if ("builtin".equalsIgnoreCase(entity.getSource())) {
            throw new AgentException("provider_immutable",
                    "Built-in providers cannot be deleted — set enabled=false to hide them", null);
        }
        providerMapper.deleteById(id);
        // Cascade the shipped catalog rows too — they're only meaningful for a
        // present provider.
        predefinedMapper.delete(new LambdaQueryWrapper<PredefinedModelEntity>()
                .eq(PredefinedModelEntity::getProviderName, entity.getName()));
    }

    // ---------------------------------------------------------- predefined models

    public List<PredefinedModelEntity> listPredefined(String providerName) {
        return predefinedMapper.selectList(new LambdaQueryWrapper<PredefinedModelEntity>()
                .eq(PredefinedModelEntity::getProviderName, providerName)
                .orderByAsc(PredefinedModelEntity::getSortOrder)
                .orderByAsc(PredefinedModelEntity::getModel));
    }

    public PredefinedModelEntity findPredefined(String providerName, String model, ModelType type) {
        return predefinedMapper.selectOne(new LambdaQueryWrapper<PredefinedModelEntity>()
                .eq(PredefinedModelEntity::getProviderName, providerName)
                .eq(PredefinedModelEntity::getModel, model)
                .eq(PredefinedModelEntity::getModelType, type)
                .last("limit 1"));
    }

    /**
     * Upsert a predefined model entry. Existing rows keyed by (provider, model,
     * model_type) are refreshed. Called by the seeder AND the admin API — same
     * idempotent path.
     * <p>
     * {@code tenant_id} defaults to {@link #SYSTEM_TENANT} — the shared global
     * catalog. Pass a specific tenant when adding a per-tenant custom entry.
     */
    @Transactional
    public PredefinedModelEntity upsertPredefined(PredefinedModelEntity next) {
        if (next.getTenantId() == null || next.getTenantId().isBlank()) {
            next.setTenantId(SYSTEM_TENANT);
        }
        PredefinedModelEntity existing = findPredefined(next.getProviderName(),
                next.getModel(), next.getModelType());
        if (existing == null) {
            if (next.getSortOrder() == null) next.setSortOrder(100);
            predefinedMapper.insert(next);
            return next;
        }
        existing.setLabel(next.getLabel());
        existing.setFeatures(next.getFeatures());
        existing.setContextLength(next.getContextLength());
        existing.setDimensions(next.getDimensions());
        existing.setParameterRules(next.getParameterRules());
        if (next.getSortOrder() != null) existing.setSortOrder(next.getSortOrder());
        predefinedMapper.updateById(existing);
        return existing;
    }

    @Transactional
    public void deletePredefined(String id) {
        predefinedMapper.deleteById(id);
    }

    /**
     * Delete every tenant-scoped predefined row for {@code providerName} — those
     * were materialized on the fly by the "启用一个远程模型" flow (see
     * {@code ModelController#setModelEnabled}). Global rows (tenant_id={@link
     * #SYSTEM_TENANT}) are untouched — they are the provider's shipped catalog
     * and belong to the metadata layer, not the credential.
     * <p>
     * Called from the credential-delete cascade so re-adding an API key doesn't
     * resurrect a stale materialized catalog the user thought was removed.
     */
    @Transactional
    public int deleteTenantPredefined(String tenantId, String providerName) {
        String tenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        if (SYSTEM_TENANT.equals(tenant)) return 0;
        return predefinedMapper.delete(new LambdaQueryWrapper<PredefinedModelEntity>()
                .eq(PredefinedModelEntity::getTenantId, tenant)
                .eq(PredefinedModelEntity::getProviderName, providerName));
    }

    // ---------------------------------------------------------- JSON codecs

    public Set<ModelType> deserializeModelTypes(String json) {
        if (json == null || json.isBlank()) return EnumSet.noneOf(ModelType.class);
        List<String> names = JsonUtils.parseList(json, String.class);
        Set<ModelType> out = EnumSet.noneOf(ModelType.class);
        for (String n : names) {
            try {
                out.add(ModelType.valueOf(n.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignore) {
                // Skip unknown types — forward-compat for a rolling deploy.
            }
        }
        return out;
    }

    public List<CredentialField> deserializeCredentialSchema(String json) {
        if (json == null || json.isBlank()) return List.of();
        return JsonUtils.parseList(json, CredentialField.class);
    }

    public Map<ModelType, List<ModelParameterRule>> deserializeParameterRules(String json) {
        Map<ModelType, List<ModelParameterRule>> out = new EnumMap<>(ModelType.class);
        if (json == null || json.isBlank()) return out;
        Map<String, List<ModelParameterRule>> raw = JsonUtils.parse(json,
                new TypeReference<Map<String, List<ModelParameterRule>>>() {});
        if (raw == null) return out;
        for (var e : raw.entrySet()) {
            try {
                out.put(ModelType.valueOf(e.getKey().toUpperCase(Locale.ROOT)), e.getValue());
            } catch (IllegalArgumentException ignore) {
                // Skip unknown types.
            }
        }
        return out;
    }

    public List<ModelParameterRule> deserializeRuleList(String json) {
        if (json == null || json.isBlank()) return List.of();
        return JsonUtils.parseList(json, ModelParameterRule.class);
    }

    public Set<ModelFeature> deserializeFeatures(String json) {
        if (json == null || json.isBlank()) return Set.of();
        List<String> raw = JsonUtils.parseList(json, String.class);
        Set<ModelFeature> out = EnumSet.noneOf(ModelFeature.class);
        for (String n : raw) {
            try {
                out.add(ModelFeature.valueOf(n));
            } catch (IllegalArgumentException ignore) {
                // ignore
            }
        }
        return out;
    }

    // ---------------------------------------------------------- helpers

    /**
     * Build a {@link PredefinedModelEntity} from an in-memory {@link PredefinedModel}
     * as shipped by a Java built-in provider. Used by the seeder.
     */
    public PredefinedModelEntity fromMemory(String providerName, PredefinedModel m, int sortOrder) {
        PredefinedModelEntity e = new PredefinedModelEntity();
        e.setTenantId(SYSTEM_TENANT);
        e.setProviderName(providerName);
        e.setModel(m.getModel());
        e.setLabel(m.getLabel() == null ? m.getModel() : m.getLabel());
        e.setModelType(m.getModelType());
        e.setContextLength(m.getContextLength());
        e.setDimensions(m.getDimensions());
        e.setSortOrder(sortOrder);
        if (m.getFeatures() != null && !m.getFeatures().isEmpty()) {
            List<String> names = new ArrayList<>();
            for (ModelFeature f : m.getFeatures()) names.add(f.name());
            e.setFeatures(JsonUtils.toJson(names));
        }
        if (m.getParameterRules() != null && !m.getParameterRules().isEmpty()) {
            e.setParameterRules(JsonUtils.toJson(m.getParameterRules()));
        }
        return e;
    }

    // ---------------------------------------------------------- private

    private static Collection<String> effectiveTenants(String tenantId) {
        String tenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        Map<String, Boolean> out = new LinkedHashMap<>();
        out.put(SYSTEM_TENANT, true);
        out.put(tenant, true);
        return out.keySet();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Boolean bool(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v));
    }
}
