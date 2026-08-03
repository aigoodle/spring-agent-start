package io.github.aigoodle.agent.service;

import io.github.aigoodle.agent.api.AgentStrategyType;
import io.github.aigoodle.agent.entity.AppModelConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppModelConfigFactoryTest {

    @Test
    void mapsRuntimeSettingsUsingTheEditorsPreferredPrompt() {
        CreateAgentRequest request = CreateAgentRequest.builder()
                .instructions("Legacy instructions")
                .prePrompt("Explicit system prompt")
                .modelProvider("qwen")
                .modelName("qwen-plus")
                .strategy(AgentStrategyType.PLAN_EXECUTE)
                .toolNames(List.of("search"))
                .datasetIds(List.of("dataset-1"))
                .modelSettings(Map.of("temperature", 0.2))
                .maxIterations(8)
                .memoryWindow(30)
                .memoryEnabled(true)
                .build();

        AppModelConfig configuration = AppModelConfigFactory.from(request);

        assertThat(configuration.getPrePrompt()).isEqualTo("Explicit system prompt");
        assertThat(configuration.getModelProvider()).isEqualTo("qwen");
        assertThat(configuration.getModelName()).isEqualTo("qwen-plus");
        assertThat(configuration.getStrategy()).isEqualTo("PLAN_EXECUTE");
        assertThat(configuration.getToolNamesJson()).isEqualTo("[\"search\"]");
        assertThat(configuration.getDatasetIdsJson()).isEqualTo("[\"dataset-1\"]");
        assertThat(configuration.getConfigs()).contains("\"temperature\":0.2");
        assertThat(configuration.getMaxIterations()).isEqualTo(8);
        assertThat(configuration.getMemoryWindow()).isEqualTo(30);
    }

    @Test
    void fallsBackToLegacyInstructionsAndLeavesNonPositiveLimitsUnspecified() {
        CreateAgentRequest request = CreateAgentRequest.builder()
                .instructions("Legacy instructions")
                .prePrompt(" ")
                .maxIterations(-1)
                .memoryWindow(0)
                .build();

        AppModelConfig configuration = AppModelConfigFactory.from(request);

        assertThat(configuration.getPrePrompt()).isEqualTo("Legacy instructions");
        assertThat(configuration.getMaxIterations()).isNull();
        assertThat(configuration.getMemoryWindow()).isNull();
    }

    @Test
    void keepsNullableEditorPayloadsAbsentInsteadOfSerializingJsonNull() {
        CreateAgentRequest request = CreateAgentRequest.builder().build();
        request.setModelSettings(null);
        request.setUserInputForm(null);
        request.setFileUpload(null);
        request.setRetrievalConfig(null);

        AppModelConfig configuration = AppModelConfigFactory.from(request);

        assertThat(configuration.getConfigs()).isNull();
        assertThat(configuration.getUserInputFormJson()).isNull();
        assertThat(configuration.getFileUploadJson()).isNull();
        assertThat(configuration.getDatasetConfigsJson()).isNull();
    }

    @Test
    void returnsNoSidecarForANullRequest() {
        assertThat(AppModelConfigFactory.from(null)).isNull();
    }
}
