package io.github.aigoodle.web.controller;

import io.github.aigoodle.agent.entity.AgentVersionEntity;
import io.github.aigoodle.agent.service.AgentVersionService;
import io.github.aigoodle.common.context.CurrentUser;
import io.github.aigoodle.common.context.UserContextHolder;
import io.github.aigoodle.connector.channel.ChannelAuditService;
import io.github.aigoodle.web.support.DefaultChannelAdministrationPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentVersionControllerTest {
    @AfterEach void clearContext() { UserContextHolder.clear(); }

    @Test void administratorPublishesWithinTrustedTenantAndAuditContainsVersionEvidence() {
        AgentVersionService versions = mock(AgentVersionService.class);
        ChannelAuditService audits = mock(ChannelAuditService.class);
        AgentVersionEntity published = new AgentVersionEntity();
        published.setId("version-2"); published.setTenantId("tenant-a"); published.setAppId("agent-1");
        published.setVersionNumber(2); published.setStatus("ACTIVE");
        when(versions.publish("tenant-a", "agent-1", "admin-1", "release")).thenReturn(published);
        AgentVersionController controller = new AgentVersionController(
                versions, new DefaultChannelAdministrationPolicy(), audits);
        UserContextHolder.set(CurrentUser.builder().tenantId("tenant-a").userId("admin-1")
                .roles(Set.of("ADMIN")).build());

        controller.publish("agent-1", Map.of("summary", "release"));

        verify(versions).publish("tenant-a", "agent-1", "admin-1", "release");
        verify(audits).success(eq("AGENT_VERSION_PUBLISH"), eq("AGENT"), eq("agent-1"),
                argThat(details -> "version-2".equals(details.get("versionId"))
                        && Integer.valueOf(2).equals(details.get("versionNumber"))));
    }

    @Test void nonAdministratorCannotRollbackAgentVersion() {
        AgentVersionService versions = mock(AgentVersionService.class);
        AgentVersionController controller = new AgentVersionController(
                versions, new DefaultChannelAdministrationPolicy(), mock(ChannelAuditService.class));
        UserContextHolder.set(CurrentUser.builder().tenantId("tenant-a").userId("employee-1")
                .roles(Set.of("EMPLOYEE")).build());

        assertThatThrownBy(() -> controller.rollback("agent-1", "version-1", Map.of()))
                .hasMessageContaining("403");
        verifyNoInteractions(versions);
    }
}
