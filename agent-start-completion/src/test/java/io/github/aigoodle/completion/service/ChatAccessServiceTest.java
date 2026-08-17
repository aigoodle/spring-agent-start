package io.github.aigoodle.completion.service;

import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.agent.service.AgentService;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.workflow.entity.WorkflowEntity;
import io.github.aigoodle.workflow.service.WorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatAccessServiceTest {

    @Test
    void tenantCanResolveItsOwnApplication() {
        AgentService agents = mock(AgentService.class);
        AgentEntity app = app("app-1", "tenant-a");
        when(agents.require("app-1")).thenReturn(app);
        ChatAccessService access = new ChatAccessService(agents, emptyProvider());

        assertThat(access.requireOwnedApp("app-1", "tenant-a")).isEqualTo("app-1");
    }

    @Test
    void rejectsApplicationOwnedByAnotherTenant() {
        AgentService agents = mock(AgentService.class);
        when(agents.require("app-1")).thenReturn(app("app-1", "tenant-b"));
        ChatAccessService access = new ChatAccessService(agents, emptyProvider());

        assertThatThrownBy(() -> access.requireOwnedApp("app-1", "tenant-a"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("无权访问");
    }

    @Test
    void rejectsWorkflowOverrideFromAnotherApplication() {
        AgentService agents = mock(AgentService.class);
        when(agents.require("app-1")).thenReturn(app("app-1", "tenant-a"));
        WorkflowService workflows = mock(WorkflowService.class);
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId("workflow-2");
        workflow.setAppId("app-2");
        workflow.setTenantId("tenant-a");
        when(workflows.require("workflow-2")).thenReturn(workflow);
        ChatAccessService access = new ChatAccessService(agents, providerOf(workflows));

        assertThatThrownBy(() -> access.requireOwnedWorkflow(
                "app-1", "tenant-a", "workflow-2"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("不属于当前应用");
    }

    @Test
    void externalApiRequiresPublishedEnabledApplication() {
        AgentService agents = mock(AgentService.class);
        AgentEntity app = app("app-1", "tenant-a");
        app.setPublished(false);
        app.setEnableApi(true);
        when(agents.require("app-1")).thenReturn(app);
        ChatAccessService access = new ChatAccessService(agents, emptyProvider());

        assertThatThrownBy(() -> access.requireExternalApp("app-1"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("尚未发布");

        app.setPublished(true);
        assertThat(access.requireExternalApp("app-1")).isEqualTo("app-1");
    }

    @Test
    void resolvesRootGlobalApplicationForCurrentTenantByCode() {
        AgentService agents = mock(AgentService.class);
        AgentEntity shared = app("root-app", "root");
        shared.setAppCode("campus-admin-assistant");
        shared.setVisibility("GLOBAL");
        when(agents.requireVisibleByCode(
                "campus-admin-assistant", "tenant-a", "root"))
                .thenReturn(shared);
        ChatAccessService access = new ChatAccessService(
                agents, emptyProvider(), "root");

        assertThat(access.requireVisibleAppByCode(
                "campus-admin-assistant", "tenant-a")).isSameAs(shared);
    }

    private static AgentEntity app(String id, String tenantId) {
        AgentEntity app = new AgentEntity();
        app.setId(id);
        app.setTenantId(tenantId);
        return app;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        return mock(ObjectProvider.class);
    }
}
