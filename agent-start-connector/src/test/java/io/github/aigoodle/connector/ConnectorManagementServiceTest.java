package io.github.aigoodle.connector;

import io.github.aigoodle.connector.connection.ConnectorConnectionService;
import io.github.aigoodle.connector.connection.ConnectorSecretCodec;
import io.github.aigoodle.connector.execution.ConnectorExecutionQueryService;
import io.github.aigoodle.connector.persistence.ConnectorConnectionEntity;
import io.github.aigoodle.connector.persistence.ConnectorConnectionMapper;
import io.github.aigoodle.connector.persistence.ConnectorExecutionEntity;
import io.github.aigoodle.connector.persistence.ConnectorExecutionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConnectorManagementServiceTest {
    @Test
    void connectionTestValidatesEncryptedPayloadWithoutReturningSecrets() {
        ConnectorConnectionMapper mapper = mock(ConnectorConnectionMapper.class);
        ConnectorSecretCodec codec = mock(ConnectorSecretCodec.class);
        ConnectorConnectionEntity entity = new ConnectorConnectionEntity();
        entity.setId("connection-1"); entity.setTenantId("acme");
        entity.setEncryptedCredentials("ciphertext"); entity.setEncryptedConfig("config-ciphertext");
        when(mapper.selectOne(any())).thenReturn(entity);
        when(codec.decode(eq("acme"), any())).thenReturn(Map.of("token", "secret"));

        var result = new ConnectorConnectionService(mapper, codec).test("connection-1", "acme");

        assertThat(result.success()).isTrue();
        assertThat(result.message()).doesNotContain("secret");
        assertThat(entity.getStatus()).isEqualTo("CONFIGURED");
        assertThat(entity.getLastTestedAt()).isNotNull();
        verify(mapper).update(eq(entity), any());
    }

    @Test
    void executionQueryIsTenantScopedAndLimited() {
        ConnectorExecutionMapper mapper = mock(ConnectorExecutionMapper.class);
        ConnectorExecutionEntity row = new ConnectorExecutionEntity(); row.setTenantId("acme");
        when(mapper.selectList(any())).thenReturn(List.of(row));
        var service = new ConnectorExecutionQueryService(mapper);

        assertThat(service.list("acme", "openclaw", "qq", "FAILED", 10)).containsExactly(row);
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<ConnectorExecutionEntity>> query =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(mapper).selectList(query.capture());
        assertThat(query.getValue().getSqlSegment()).contains("tenant_id", "provider", "connector_id", "status", "limit 10");
    }
}
