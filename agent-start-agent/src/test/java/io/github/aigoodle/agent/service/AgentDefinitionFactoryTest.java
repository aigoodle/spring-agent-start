package io.github.aigoodle.agent.service;

import io.github.aigoodle.agent.api.AgentDefinition;
import io.github.aigoodle.agent.api.AgentStrategyType;
import io.github.aigoodle.agent.entity.AgentEntity;
import io.github.aigoodle.agent.entity.AppModelConfig;
import io.github.aigoodle.common.exception.AgentException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentDefinitionFactoryTest {

    @Test
    void createsRunnableDefaultsWhenNoSidecarExists() {
        AppModelConfigService modelConfigService = mock(AppModelConfigService.class);
        AgentDefinitionFactory definitionFactory = new AgentDefinitionFactory(modelConfigService);
        AgentEntity agent = agent("agent-1");
        agent.setModelProvider("openai");
        agent.setModelName("gpt-test");

        AgentDefinition definition = definitionFactory.create(agent);

        assertThat(definition.getModelProvider()).isEqualTo("openai");
        assertThat(definition.getModelName()).isEqualTo("gpt-test");
        assertThat(definition.getStrategy()).isEqualTo(AgentStrategyType.REACT);
        assertThat(definition.getMaxIterations()).isEqualTo(6);
        assertThat(definition.getMemoryWindow()).isEqualTo(20);
        assertThat(definition.isMemoryEnabled()).isTrue();
        assertThat(definition.getToolNames()).isEmpty();
        assertThat(definition.getModelSettings()).isEmpty();
    }

    @Test
    void acceptsHumanFriendlyStrategyNamesAndBlankJsonFields() {
        AppModelConfigService modelConfigService = mock(AppModelConfigService.class);
        AppModelConfig modelConfig = new AppModelConfig();
        modelConfig.setStrategy(" function-calling ");
        modelConfig.setToolNamesJson(" ");
        modelConfig.setApprovalToolsJson("");
        modelConfig.setDelegateAgentIdsJson(" ");
        modelConfig.setConfigs("");
        when(modelConfigService.findByAppId("agent-1")).thenReturn(modelConfig);
        AgentDefinitionFactory definitionFactory = new AgentDefinitionFactory(modelConfigService);

        AgentDefinition definition = definitionFactory.create(agent("agent-1"));

        assertThat(definition.getStrategy()).isEqualTo(AgentStrategyType.FUNCTION_CALLING);
        assertThat(definition.getToolNames()).isEmpty();
        assertThat(definition.getApprovalRequiredTools()).isEmpty();
        assertThat(definition.getDelegateAgentIds()).isEmpty();
        assertThat(definition.getModelSettings()).isEmpty();
    }

    @Test
    void reportsUnsupportedStrategiesAsDomainErrors() {
        AppModelConfigService modelConfigService = mock(AppModelConfigService.class);
        AppModelConfig modelConfig = new AppModelConfig();
        modelConfig.setStrategy("guess-and-hope");
        when(modelConfigService.findByAppId("agent-1")).thenReturn(modelConfig);
        AgentDefinitionFactory definitionFactory = new AgentDefinitionFactory(modelConfigService);

        assertThatThrownBy(() -> definitionFactory.create(agent("agent-1")))
                .isInstanceOfSatisfying(AgentException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("invalid_agent_strategy");
                    assertThat(exception).hasMessageContaining("guess-and-hope");
                });
    }

    @Test
    void sidecarModelOnlyOverridesCatalogValuesWhenItHasText() {
        AppModelConfigService modelConfigService = mock(AppModelConfigService.class);
        AppModelConfig modelConfig = new AppModelConfig();
        modelConfig.setModelProvider(" ");
        modelConfig.setModelName("sidecar-model");
        when(modelConfigService.findByAppId("agent-1")).thenReturn(modelConfig);
        AgentDefinitionFactory definitionFactory = new AgentDefinitionFactory(modelConfigService);
        AgentEntity agent = agent("agent-1");
        agent.setModelProvider("catalog-provider");
        agent.setModelName("catalog-model");

        AgentEntity enriched = definitionFactory.enrich(agent);

        assertThat(enriched.getModelProvider()).isEqualTo("catalog-provider");
        assertThat(enriched.getModelName()).isEqualTo("sidecar-model");
    }

    private static AgentEntity agent(String agentId) {
        AgentEntity agent = new AgentEntity();
        agent.setId(agentId);
        agent.setTenantId("default");
        agent.setName("Researcher");
        return agent;
    }
}
