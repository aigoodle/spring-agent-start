package io.github.aigoodle.agent.service;

import io.github.aigoodle.agent.api.AgentDefinition;
import io.github.aigoodle.agent.api.AgentRequest;
import io.github.aigoodle.agent.api.AgentResponse;
import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.agent.hitl.ApprovalGate;
import io.github.aigoodle.agent.mapper.AgentMapper;
import io.github.aigoodle.agent.memory.AgentMemory;
import io.github.aigoodle.agent.strategy.AgentStrategy;
import io.github.aigoodle.agent.strategy.AgentStrategyRegistry;
import io.github.aigoodle.model.service.ModelService;
import io.github.aigoodle.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentServiceLifecycleTest {

    @Test
    void createsAnAgentInTheDefaultTenantWhenTenantIsBlank() {
        Dependencies dependencies = new Dependencies();
        doAnswer(invocation -> {
            invocation.<AgentEntity>getArgument(0).setId("agent-1");
            return 1;
        }).when(dependencies.agentMapper).insert(any(AgentEntity.class));
        AgentService agentService = dependencies.createService();

        agentService.create(CreateAgentRequest.builder().tenantId(" ").name("Researcher").build());

        ArgumentCaptor<AgentEntity> insertedAgent = ArgumentCaptor.forClass(AgentEntity.class);
        verify(dependencies.agentMapper).insert(insertedAgent.capture());
        assertThat(insertedAgent.getValue().getTenantId()).isEqualTo("default");
        assertThat(insertedAgent.getValue().getName()).isEqualTo("Researcher");
    }

    @Test
    void deletesTheModelSidecarBeforeItsCatalogEntry() {
        Dependencies dependencies = new Dependencies();
        AgentService agentService = dependencies.createService();

        agentService.delete("agent-1");

        InOrder deletionOrder = inOrder(dependencies.modelConfigService, dependencies.agentMapper);
        deletionOrder.verify(dependencies.modelConfigService).deleteByAppId("agent-1");
        deletionOrder.verify(dependencies.agentMapper).deleteById("agent-1");
    }

    @Test
    void generatesAConversationIdWhenTheRequestContainsOnlyWhitespace() {
        Dependencies dependencies = new Dependencies();
        AgentStrategy strategy = mock(AgentStrategy.class);
        AgentResponse strategyResponse = new AgentResponse();
        when(dependencies.strategyRegistry.get(any())).thenReturn(strategy);
        when(strategy.run(any())).thenReturn(strategyResponse);
        when(dependencies.modelService.getChatClient(anyString(), anyString(), anyString()))
                .thenReturn(mock(ChatClient.class));
        AgentService agentService = dependencies.createService();
        AgentDefinition definition = AgentDefinition.builder()
                .id("agent-1")
                .tenantId("default")
                .name("Researcher")
                .modelProvider("openai")
                .modelName("gpt-test")
                .toolNames(List.of("unregistered-tool"))
                .memoryEnabled(false)
                .build();

        AgentResponse response = agentService.runDefinition(
                definition, AgentRequest.builder().query("Hello").conversationId(" ").build());

        assertThat(response.getConversationId()).isNotBlank();
        assertThat(response.getConversationId()).isNotEqualTo(" ");
    }

    private static final class Dependencies {

        private final AgentMapper agentMapper = mock(AgentMapper.class);
        private final AppModelConfigService modelConfigService = mock(AppModelConfigService.class);
        private final ModelService modelService = mock(ModelService.class);
        private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
        private final AgentStrategyRegistry strategyRegistry = mock(AgentStrategyRegistry.class);
        private final AgentMemory memory = mock(AgentMemory.class);
        private final ApprovalGate approvalGate = mock(ApprovalGate.class);

        AgentService createService() {
            return new AgentService(agentMapper, modelConfigService, modelService, toolRegistry,
                    strategyRegistry, memory, approvalGate);
        }
    }
}
