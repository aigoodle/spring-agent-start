package io.github.aigoodle.agent.service;

import io.github.aigoodle.agent.entity.ApiTokenEntity;
import io.github.aigoodle.agent.mapper.ApiTokenMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ApiTokenServiceTest {

    @Test
    void createsOpaqueTokenWithNormalizedDefaults() {
        ApiTokenMapper tokenMapper = mock(ApiTokenMapper.class);
        ApiTokenService tokenService = new ApiTokenService(tokenMapper);

        ApiTokenEntity apiToken = tokenService.create("app-1", " ", null, "");

        ArgumentCaptor<ApiTokenEntity> insertedToken =
                ArgumentCaptor.forClass(ApiTokenEntity.class);
        verify(tokenMapper).insert(insertedToken.capture());
        assertThat(apiToken).isSameAs(insertedToken.getValue());
        assertThat(apiToken.getTenantId()).isEqualTo("default");
        assertThat(apiToken.getName()).isEqualTo("default");
        assertThat(apiToken.getType()).isEqualTo("app");
        assertThat(apiToken.getToken()).startsWith("app-").doesNotContain("=");
    }

    @Test
    void lastUsedUpdateNeverDisruptsRequestPath() {
        ApiTokenMapper tokenMapper = mock(ApiTokenMapper.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(tokenMapper).updateById(any(ApiTokenEntity.class));
        ApiTokenService tokenService = new ApiTokenService(tokenMapper);

        assertThatCode(() -> tokenService.touchLastUsed("token-1"))
                .doesNotThrowAnyException();
    }
}
