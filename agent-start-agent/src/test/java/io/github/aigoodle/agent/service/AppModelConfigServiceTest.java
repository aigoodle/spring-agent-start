package io.github.aigoodle.agent.service;

import io.github.aigoodle.agent.entity.AppModelConfig;
import io.github.aigoodle.agent.mapper.AppModelConfigMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppModelConfigServiceTest {

    @Test
    void insertsAConfigurationUsingTheApplicationIdentity() {
        AppModelConfigMapper configMapper = mock(AppModelConfigMapper.class);
        AppModelConfigService service = new AppModelConfigService(configMapper);
        AppModelConfig configuration = new AppModelConfig();

        AppModelConfig saved = service.upsert(new AppModelConfigRegistration(
                "app-1", "tenant-1", configuration));

        assertThat(saved).isSameAs(configuration);
        assertThat(configuration.getId()).isEqualTo("app-1");
        assertThat(configuration.getAppId()).isEqualTo("app-1");
        assertThat(configuration.getTenantId()).isEqualTo("tenant-1");
        verify(configMapper).insert(configuration);
    }

    @Test
    void patchesTheExistingSidecarInsteadOfReplacingItsIdentity() {
        AppModelConfigMapper configMapper = mock(AppModelConfigMapper.class);
        AppModelConfigService service = new AppModelConfigService(configMapper);
        AppModelConfig existing = new AppModelConfig();
        existing.setId("app-1");
        existing.setModelName("old-model");
        AppModelConfig patch = new AppModelConfig();
        patch.setModelName("new-model");
        when(configMapper.selectById("app-1")).thenReturn(existing);

        AppModelConfig saved = service.upsert(new AppModelConfigRegistration(
                "app-1", "tenant-1", patch));

        assertThat(saved).isSameAs(existing);
        assertThat(existing.getId()).isEqualTo("app-1");
        assertThat(existing.getModelName()).isEqualTo("new-model");
        verify(configMapper).updateById(existing);
    }
}
