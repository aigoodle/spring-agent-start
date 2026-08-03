package io.github.aigoodle.agent.service;

import io.github.aigoodle.agent.api.AgentDefinition;
import io.github.aigoodle.agent.api.AgentRequest;
import io.github.aigoodle.agent.api.AgentResponse;
import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.agent.mapper.AgentMapper;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.tool.AgentTool;
import io.github.aigoodle.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentToolResolverTest {

    @Test
    void exposesAllRegisteredToolsWhenNoWhitelistIsConfigured() {
        AgentTool search = new NamedTool("search");
        AgentTool calculator = new NamedTool("calculator");
        AgentToolResolver resolver = resolver(
                mock(AgentMapper.class), new ToolRegistry(List.of(search, calculator), List.of()));

        AgentDefinition definition = definition("agent-1");
        definition.setToolNames(List.of());

        List<AgentTool> resolved = resolver.resolve(definition, this::emptyRun);

        assertThat(resolved).extracting(AgentTool::name)
                .containsExactly("search", "calculator");
    }

    @Test
    void resolvesAWhitelistOnceAndSkipsBlankOrUnknownNames() {
        AgentTool search = new NamedTool("search");
        AgentToolResolver resolver = resolver(
                mock(AgentMapper.class), new ToolRegistry(List.of(search), List.of()));
        AgentDefinition definition = definition("agent-1");
        definition.setToolNames(List.of("search", " ", "missing", "search"));

        List<AgentTool> resolved = resolver.resolve(definition, this::emptyRun);

        assertThat(resolved).extracting(AgentTool::name).containsExactly("search");
    }

    @Test
    void rejectsSelfDelegationBeforeLoadingTheAgent() {
        AgentMapper agentMapper = mock(AgentMapper.class);
        AgentToolResolver resolver = resolver(agentMapper, emptyRegistry());
        AgentDefinition definition = definition("agent-1");
        definition.setDelegateAgentIds(List.of("agent-1"));

        assertThatThrownBy(() -> resolver.resolve(definition, this::emptyRun))
                .isInstanceOfSatisfying(AgentException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("invalid_agent_delegation"));
        verify(agentMapper, never()).selectById("agent-1");
    }

    @Test
    void rejectsDelegationAcrossTenantBoundaries() {
        AgentMapper agentMapper = mock(AgentMapper.class);
        AgentEntity delegate = delegate("worker-1", "tenant-b", "Worker");
        when(agentMapper.selectById("worker-1")).thenReturn(delegate);
        AgentToolResolver resolver = resolver(agentMapper, emptyRegistry());
        AgentDefinition definition = definition("agent-1");
        definition.setTenantId("tenant-a");
        definition.setDelegateAgentIds(List.of("worker-1"));

        assertThatThrownBy(() -> resolver.resolve(definition, this::emptyRun))
                .isInstanceOfSatisfying(AgentException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("delegate_cross_tenant"));
    }

    @Test
    void buildsAStableToolNameWhenTheDisplayNameHasNoAsciiCharacters() {
        AgentMapper agentMapper = mock(AgentMapper.class);
        AgentEntity delegate = delegate("worker-1", " ", "研究助手");
        when(agentMapper.selectById("worker-1")).thenReturn(delegate);
        AgentToolResolver resolver = resolver(agentMapper, emptyRegistry());
        AgentDefinition definition = definition("agent-1");
        definition.setTenantId(null);
        definition.setDelegateAgentIds(List.of("worker-1", "worker-1", " "));

        List<AgentTool> resolved = resolver.resolve(definition, this::emptyRun);

        assertThat(resolved).hasSize(1);
        assertThat(resolved.getFirst().name()).isEqualTo("delegate_to_worker_1");
        assertThat(resolved.getFirst().description())
                .isEqualTo("Delegate a subtask to the '研究助手' agent.");
        verify(agentMapper).selectById("worker-1");
    }

    private AgentResponse emptyRun(String agentId, AgentRequest request) {
        return new AgentResponse();
    }

    private static AgentToolResolver resolver(AgentMapper agentMapper, ToolRegistry toolRegistry) {
        return new AgentToolResolver(
                agentMapper, mock(AppModelConfigService.class), toolRegistry);
    }

    private static ToolRegistry emptyRegistry() {
        return new ToolRegistry(List.of(), List.of());
    }

    private static AgentDefinition definition(String agentId) {
        return AgentDefinition.builder()
                .id(agentId)
                .tenantId("default")
                .toolNames(List.of("unregistered"))
                .build();
    }

    private static AgentEntity delegate(String agentId, String tenantId, String name) {
        AgentEntity agent = new AgentEntity();
        agent.setId(agentId);
        agent.setTenantId(tenantId);
        agent.setName(name);
        return agent;
    }

    private record NamedTool(String name) implements AgentTool {

        @Override
        public String description() {
            return name;
        }

        @Override
        public Object execute(Map<String, Object> arguments) {
            return null;
        }
    }
}
