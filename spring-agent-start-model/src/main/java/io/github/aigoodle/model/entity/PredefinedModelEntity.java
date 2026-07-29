package io.github.aigoodle.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.aigoodle.common.persistence.BaseEntity;
import io.github.aigoodle.model.enums.ModelType;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * The provider's shipped model catalog — DB-driven replacement for the Java
 * {@code ModelProvider.predefinedModels()} list. Seeded from Java on first boot;
 * afterwards editable via the admin API (which lets you add a new model to an
 * existing provider without redeploying).
 * <p>
 * Rows here are <em>global</em> per provider — {@code tenant_id} is NOT modelled
 * because catalog knowledge is shared. Tenant selection state (enabled /
 * disabled / default) lives in
 * {@link ProviderModelSettingEntity} and {@link TenantDefaultModelEntity}.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_predefined_model")
public class PredefinedModelEntity extends BaseEntity {

    /** FK to {@link ProviderDefinitionEntity#getName()}. */
    private String providerName;

    /** e.g. {@code "gpt-4o"}. */
    private String model;

    private String label;

    private ModelType modelType;

    /** JSON array of {@link io.github.aigoodle.model.enums.ModelFeature} names. */
    private String features;

    private Integer contextLength;

    private Integer dimensions;

    /**
     * JSON array of {@link io.github.aigoodle.model.provider.ModelParameterRule}
     * — overrides the provider's default rules when set.
     */
    private String parameterRules;

    private Integer sortOrder;
}
