package io.github.aigoodle.agent.service;

import io.github.aigoodle.agent.entity.AppModelConfig;
import io.github.aigoodle.agent.mapper.AppModelConfigMapper;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the one-to-one model-configuration sidecar of every application.
 *
 * <p>The sidecar primary key is the application id. Keeping inserts, patch updates,
 * and deletes behind this service makes that invariant visible in one place.</p>
 */
public class AppModelConfigService {

    private final AppModelConfigMapper configMapper;

    public AppModelConfigService(AppModelConfigMapper configMapper) {
        this.configMapper = configMapper;
    }

    /** Load a sidecar by application id, or return {@code null} when none exists. */
    public AppModelConfig findByAppId(String appId) {
        if (appId == null || appId.isBlank()) {
            return null;
        }
        return configMapper.selectById(appId);
    }

    /** Insert a new sidecar or apply the supplied non-null fields to the existing one. */
    @Transactional
    public AppModelConfig upsert(AppModelConfigRegistration registration) {
        AppModelConfig configuration = registration.configuration();
        if (configuration == null) {
            return null;
        }

        String appId = registration.appId();
        AppModelConfig existingConfiguration = configMapper.selectById(appId);
        prepareIdentity(configuration, registration);
        if (existingConfiguration == null) {
            configMapper.insert(configuration);
            return configuration;
        }

        AppModelConfigPatch.apply(existingConfiguration, configuration);
        configMapper.updateById(existingConfiguration);
        return existingConfiguration;
    }

    /** @deprecated Use {@link #upsert(AppModelConfigRegistration)}. */
    @Deprecated(forRemoval = false)
    public AppModelConfig upsert(String appId, String tenantId, AppModelConfig configuration) {
        return upsert(new AppModelConfigRegistration(appId, tenantId, configuration));
    }

    /** Delete the sidecar owned by an application. */
    @Transactional
    public void deleteByAppId(String appId) {
        if (appId == null || appId.isBlank()) {
            return;
        }
        configMapper.deleteById(appId);
    }

    /** Translate the flat agent-editor request into its persistence sidecar. */
    public static AppModelConfig fromRequest(CreateAgentRequest request) {
        return AppModelConfigFactory.from(request);
    }

    private static void prepareIdentity(
            AppModelConfig configuration, AppModelConfigRegistration registration) {
        configuration.setAppId(registration.appId());
        configuration.setId(registration.appId());
        if (registration.tenantId() != null && !registration.tenantId().isBlank()) {
            configuration.setTenantId(registration.tenantId());
        }
    }
}
