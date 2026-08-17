package io.github.aigoodle.agent.service;

import io.github.aigoodle.agent.entity.AppApiTokenEntity;
import io.github.aigoodle.agent.mapper.AppApiTokenMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AppApiTokenServiceTest {

    @Test
    void createsOpaqueTokenWithNormalizedDefaults() {
        AppApiTokenMapper tokenMapper = mock(AppApiTokenMapper.class);
        AppApiTokenService tokenService = new AppApiTokenService(tokenMapper);

        AppApiTokenEntity apiToken = tokenService.create("app-1", " ", null, "");

        ArgumentCaptor<AppApiTokenEntity> insertedToken =
                ArgumentCaptor.forClass(AppApiTokenEntity.class);
        verify(tokenMapper).insert(insertedToken.capture());
        assertThat(apiToken).isSameAs(insertedToken.getValue());
        assertThat(apiToken.getTenantId()).isEqualTo("default");
        assertThat(apiToken.getName()).isEqualTo("default");
        assertThat(apiToken.getType()).isEqualTo("app");
        assertThat(apiToken.getToken()).startsWith("app-").doesNotContain("=");
    }

    @Test
    void lastUsedUpdateNeverDisruptsRequestPath() {
        AppApiTokenMapper tokenMapper = mock(AppApiTokenMapper.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(tokenMapper).updateById(any(AppApiTokenEntity.class));
        AppApiTokenService tokenService = new AppApiTokenService(tokenMapper);

        assertThatCode(() -> tokenService.touchLastUsed("token-1"))
                .doesNotThrowAnyException();
    }
}
