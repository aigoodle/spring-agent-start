package io.github.aigoodle.agent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.aigoodle.agent.api.AgentDefinition;
import io.github.aigoodle.agent.entity.AgentVersionEntity;
import io.github.aigoodle.agent.entity.AppEntity;
import io.github.aigoodle.agent.entity.AppModelConfigEntity;
import io.github.aigoodle.agent.mapper.AgentVersionMapper;
import io.github.aigoodle.agent.mapper.AppMapper;
import io.github.aigoodle.common.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentVersionServiceTest {
    @Test void publishPersistsAnImmutableRuntimeDefinition() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "agent-version-test"),
                AgentVersionEntity.class);
        AgentVersionMapper versions = mock(AgentVersionMapper.class);
        AppMapper apps = mock(AppMapper.class);
        AppModelConfigService configs = mock(AppModelConfigService.class);
        AppEntity app = new AppEntity();
        app.setId("agent-1"); app.setTenantId("tenant-a"); app.setName("客服");
        app.setMode("agent"); app.setModelProvider("openai"); app.setModelName("gpt-test");
        AppModelConfigEntity config = new AppModelConfigEntity();
        config.setPrePrompt("published prompt"); config.setStrategy("REACT");
        config.setRuntimeType("CUSTOM_RUNTIME"); config.setRuntimeRef("support-agent-v2");
        when(apps.selectOne(any())).thenReturn(app);
        when(configs.findByAppId("tenant-a", "agent-1")).thenReturn(config);
        when(versions.selectOne(any())).thenReturn(null);
        AgentVersionService service = new AgentVersionService(versions, apps, configs);

        AgentVersionEntity result = service.publish("tenant-a", "agent-1", "admin-1", "first release");

        ArgumentCaptor<AgentVersionEntity> inserted = ArgumentCaptor.forClass(AgentVersionEntity.class);
        verify(versions).insert(inserted.capture());
        AgentDefinition snapshot = JsonUtils.parse(inserted.getValue().getDefinitionJson(), AgentDefinition.class);
        assertThat(snapshot.getInstructions()).isEqualTo("published prompt");
        assertThat(snapshot.getRuntimeType()).isEqualTo("CUSTOM_RUNTIME");
        assertThat(snapshot.getRuntimeRef()).isEqualTo("support-agent-v2");
        assertThat(result.getVersionNumber()).isEqualTo(1);
        assertThat(result.getStatus()).isEqualTo("ACTIVE");
        assertThat(result.getPublishedBy()).isEqualTo("admin-1");

        config.setPrePrompt("draft changed later");
        assertThat(JsonUtils.parse(result.getDefinitionJson(), AgentDefinition.class).getInstructions())
                .isEqualTo("published prompt");
    }

    @Test void explicitlyPinnedSupersededVersionRemainsRunnableForExistingConversation() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "agent-version-pinned-test"),
                AgentVersionEntity.class);
        AgentVersionMapper versions = mock(AgentVersionMapper.class);
        AppMapper apps = mock(AppMapper.class);
        AgentVersionEntity old = new AgentVersionEntity();
        old.setId("version-1"); old.setTenantId("tenant-a"); old.setAppId("agent-1");
        old.setVersionNumber(1); old.setStatus("SUPERSEDED");
        when(versions.selectOne(any())).thenReturn(old);
        AgentVersionService service = new AgentVersionService(versions, apps, mock(AppModelConfigService.class));

        assertThat(service.requireRunnable("tenant-a", "agent-1", "version-1")).isSameAs(old);

        old.setStatus("DISABLED");
        assertThatThrownBy(() -> service.requireRunnable("tenant-a", "agent-1", "version-1"))
                .hasMessageContaining("not active");
    }
}
