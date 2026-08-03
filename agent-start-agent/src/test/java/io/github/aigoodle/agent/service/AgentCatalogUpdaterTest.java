package io.github.aigoodle.agent.service;

import io.github.aigoodle.agent.entity.AgentEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCatalogUpdaterTest {

    private final AgentCatalogUpdater catalogUpdater = new AgentCatalogUpdater();

    @Test
    void appliesReadableDefaultsToANewCatalogEntry() {
        AgentEntity agent = new AgentEntity();

        catalogUpdater.applyRequest(CreateAgentRequest.builder().build(), agent);

        assertThat(agent.getMode()).isEqualTo("agent");
        assertThat(agent.getStatus()).isEqualTo("normal");
        assertThat(agent.getPublished()).isTrue();
    }

    @Test
    void blankModeAndStatusDoNotEraseExistingValues() {
        AgentEntity agent = new AgentEntity();
        agent.setMode("workflow");
        agent.setStatus("disabled");
        CreateAgentRequest request = CreateAgentRequest.builder()
                .mode(" ")
                .status("")
                .name("Updated name")
                .build();

        catalogUpdater.applyRequest(request, agent);

        assertThat(agent.getMode()).isEqualTo("workflow");
        assertThat(agent.getStatus()).isEqualTo("disabled");
        assertThat(agent.getName()).isEqualTo("Updated name");
    }

    @Test
    void omittedOptionalFieldsRemainUnchanged() {
        AgentEntity agent = new AgentEntity();
        agent.setDescription("Existing description");
        agent.setEnableApi(true);

        catalogUpdater.applyRequest(CreateAgentRequest.builder().build(), agent);

        assertThat(agent.getDescription()).isEqualTo("Existing description");
        assertThat(agent.getEnableApi()).isTrue();
    }
}
