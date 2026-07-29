package io.github.aigoodle.model.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.model.entity.ProviderModelSettingEntity;
import io.github.aigoodle.model.entity.TenantDefaultModelEntity;
import io.github.aigoodle.model.enums.ModelType;
import io.github.aigoodle.model.mapper.ProviderModelSettingMapper;
import io.github.aigoodle.model.mapper.TenantDefaultModelMapper;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dify-parity per-tenant settings for models:
 * <ul>
 *   <li>{@code agent_provider_model_setting} — enable / load-balancing switch.
 *       Missing row = enabled (so the vast majority of predefined models need
 *       no row at all).</li>
 *   <li>{@code agent_tenant_default_model} — one default per model type.</li>
 * </ul>
 * <p>
 * Keyed by (tenant, provider_name, model_name, model_type) rather than by row
 * id — because the same "model" can be represented by a predefined-catalog row
 * OR a custom {@code agent_model} row, and settings apply the same way to
 * either.
 */
public class ProviderModelSettingsService {

    private static final String DEFAULT_TENANT = "default";

    private final ProviderModelSettingMapper settingMapper;
    private final TenantDefaultModelMapper defaultMapper;

    public ProviderModelSettingsService(ProviderModelSettingMapper settingMapper,
                                        TenantDefaultModelMapper defaultMapper) {
        this.settingMapper = settingMapper;
        this.defaultMapper = defaultMapper;
    }

    // -------------------------------------------------- enable / load-balance

    /**
     * True only when an explicit {@code enabled=true} row exists — <em>opt-in</em>
     * semantics. A freshly-configured provider shows every model as disabled
     * so the user picks exactly which ones they want to use (per user request:
     * "配置 API Key 之后不要自动启用任何模型").
     */
    public boolean isEnabled(String tenantId, String providerName, String modelName,
                             ModelType modelType) {
        ProviderModelSettingEntity row = findSetting(tenantId, providerName, modelName, modelType);
        return row != null && Boolean.TRUE.equals(row.getEnabled());
    }

    public ProviderModelSettingEntity findSetting(String tenantId, String providerName,
                                                  String modelName, ModelType modelType) {
        return settingMapper.selectOne(new LambdaQueryWrapper<ProviderModelSettingEntity>()
                .eq(ProviderModelSettingEntity::getTenantId, tenant(tenantId))
                .eq(ProviderModelSettingEntity::getProviderName, providerName)
                .eq(ProviderModelSettingEntity::getModelName, modelName)
                .eq(ProviderModelSettingEntity::getModelType, modelType)
                .last("limit 1"));
    }

    /**
     * Batch-read: setting rows for {@code providerName} within {@code tenantId},
     * keyed by "{modelName}::{modelType}" for cheap in-memory joins.
     */
    public Map<String, ProviderModelSettingEntity> settingIndex(String tenantId, String providerName) {
        List<ProviderModelSettingEntity> rows = settingMapper.selectList(
                new LambdaQueryWrapper<ProviderModelSettingEntity>()
                        .eq(ProviderModelSettingEntity::getTenantId, tenant(tenantId))
                        .eq(ProviderModelSettingEntity::getProviderName, providerName));
        Map<String, ProviderModelSettingEntity> out = new LinkedHashMap<>();
        for (ProviderModelSettingEntity r : rows) {
            out.put(key(r.getModelName(), r.getModelType()), r);
        }
        return out;
    }

    /**
     * Opt-in enable: default is disabled, so we persist an explicit row every
     * time the user diverges. Missing row = disabled. Row with enabled=true =
     * user turned it on. Row with enabled=false = also disabled (equivalent to
     * missing, but we keep it around if load-balancing is configured).
     */
    @Transactional
    public ProviderModelSettingEntity setEnabled(String tenantId, String providerName,
                                                 String modelName, ModelType modelType,
                                                 boolean enabled) {
        ProviderModelSettingEntity existing = findSetting(tenantId, providerName, modelName, modelType);
        if (existing == null) {
            // No row — persist an explicit setting only when turning ON. Turning OFF
            // when there's no row is a no-op (already disabled by default).
            if (!enabled) return null;
            ProviderModelSettingEntity row = new ProviderModelSettingEntity();
            row.setTenantId(tenant(tenantId));
            row.setProviderName(providerName);
            row.setModelName(modelName);
            row.setModelType(modelType);
            row.setEnabled(Boolean.TRUE);
            row.setLoadBalancingEnabled(Boolean.FALSE);
            settingMapper.insert(row);
            return row;
        }
        // Setting exists — flip flag, or drop the row when turning OFF a plain row
        // (load-balancing rows are kept so their config isn't lost).
        if (!enabled && Boolean.FALSE.equals(existing.getLoadBalancingEnabled())) {
            settingMapper.deleteById(existing.getId());
            return null;
        }
        existing.setEnabled(enabled);
        settingMapper.updateById(existing);
        return existing;
    }

    // ---------------------------------------------------------- defaults

    public TenantDefaultModelEntity getDefault(String tenantId, ModelType modelType) {
        return defaultMapper.selectOne(new LambdaQueryWrapper<TenantDefaultModelEntity>()
                .eq(TenantDefaultModelEntity::getTenantId, tenant(tenantId))
                .eq(TenantDefaultModelEntity::getModelType, modelType)
                .last("limit 1"));
    }

    public Map<ModelType, TenantDefaultModelEntity> listDefaults(String tenantId) {
        List<TenantDefaultModelEntity> rows = defaultMapper.selectList(
                new LambdaQueryWrapper<TenantDefaultModelEntity>()
                        .eq(TenantDefaultModelEntity::getTenantId, tenant(tenantId)));
        Map<ModelType, TenantDefaultModelEntity> out = new EnumMap<>(ModelType.class);
        for (TenantDefaultModelEntity r : rows) out.put(r.getModelType(), r);
        return out;
    }

    /**
     * Set the default model for {@code modelType}, replacing any previous
     * default of the same type for that tenant.
     */
    @Transactional
    public TenantDefaultModelEntity setDefault(String tenantId, String providerName,
                                               String modelName, ModelType modelType) {
        if (providerName == null || modelName == null || modelType == null) {
            throw new AgentException("invalid_default",
                    "provider_name / model_name / model_type must not be null", null);
        }
        defaultMapper.delete(new LambdaQueryWrapper<TenantDefaultModelEntity>()
                .eq(TenantDefaultModelEntity::getTenantId, tenant(tenantId))
                .eq(TenantDefaultModelEntity::getModelType, modelType));
        TenantDefaultModelEntity row = new TenantDefaultModelEntity();
        row.setTenantId(tenant(tenantId));
        row.setProviderName(providerName);
        row.setModelName(modelName);
        row.setModelType(modelType);
        defaultMapper.insert(row);
        return row;
    }

    @Transactional
    public void clearDefault(String tenantId, ModelType modelType) {
        defaultMapper.delete(new LambdaQueryWrapper<TenantDefaultModelEntity>()
                .eq(TenantDefaultModelEntity::getTenantId, tenant(tenantId))
                .eq(TenantDefaultModelEntity::getModelType, modelType));
    }

    /**
     * Nuke every enable-setting row for (tenant, provider). Called from the
     * credential-delete cascade so re-adding the API key lands on a fresh state
     * — otherwise the previous "启用" toggles resurface the moment the key is
     * saved again, which is what the user hit ("删除的时候没删干净, 再次添加还是
     * 之前的").
     */
    @Transactional
    public int clearProviderSettings(String tenantId, String providerName) {
        return settingMapper.delete(new LambdaQueryWrapper<ProviderModelSettingEntity>()
                .eq(ProviderModelSettingEntity::getTenantId, tenant(tenantId))
                .eq(ProviderModelSettingEntity::getProviderName, providerName));
    }

    /**
     * Drop every tenant-default row that pointed at {@code providerName}. Same
     * motivation as {@link #clearProviderSettings}: on credential removal the
     * "系统默认模型" for this provider is no longer resolvable, so we must not
     * keep pointing at a dangling (provider, model) pair.
     */
    @Transactional
    public int clearProviderDefaults(String tenantId, String providerName) {
        return defaultMapper.delete(new LambdaQueryWrapper<TenantDefaultModelEntity>()
                .eq(TenantDefaultModelEntity::getTenantId, tenant(tenantId))
                .eq(TenantDefaultModelEntity::getProviderName, providerName));
    }

    private static String tenant(String t) {
        return t == null || t.isBlank() ? DEFAULT_TENANT : t;
    }

    private static String key(String modelName, ModelType type) {
        return modelName + "::" + (type == null ? "" : type.name());
    }
}
