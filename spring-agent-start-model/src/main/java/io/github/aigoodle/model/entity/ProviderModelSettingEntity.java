package io.github.aigoodle.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.aigoodle.common.persistence.BaseEntity;
import io.github.aigoodle.model.enums.ModelType;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Per-tenant per-model enable / load-balancing switch. Dify semantics:
 * <em>a missing row means the model is ENABLED</em>. So a freshly-configured
 * provider gets all its predefined models enabled without having to insert 100
 * seed rows — only user-flipped-off entries are persisted.
 * <p>
 * This replaces the deprecated {@code agent_model.enabled} column for predefined
 * models (custom {@code agent_model} rows continue to use that column for
 * backward compat).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_provider_model_setting")
public class ProviderModelSettingEntity extends BaseEntity {

    private String providerName;

    private String modelName;

    private ModelType modelType;

    private Boolean enabled;

    private Boolean loadBalancingEnabled;
}
