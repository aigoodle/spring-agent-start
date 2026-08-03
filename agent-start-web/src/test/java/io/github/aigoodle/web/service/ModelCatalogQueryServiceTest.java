package io.github.aigoodle.web.service;

import io.github.aigoodle.model.entity.ModelEntity;
import io.github.aigoodle.model.entity.PredefinedModelEntity;
import io.github.aigoodle.model.entity.ProviderCredentialEntity;
import io.github.aigoodle.model.entity.ProviderDefinitionEntity;
import io.github.aigoodle.model.entity.ProviderModelSettingEntity;
import io.github.aigoodle.model.enums.ModelType;
import io.github.aigoodle.model.provider.ModelParameterRule;
import io.github.aigoodle.model.registry.ModelProviderRegistry;
import io.github.aigoodle.model.service.ModelService;
import io.github.aigoodle.model.service.ProviderCredentialService;
import io.github.aigoodle.model.service.ProviderDefinitionService;
import io.github.aigoodle.model.service.ProviderModelSettingsService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelCatalogQueryServiceTest {

    @Test
    void exposesOnlyPropertiesDeclaredByParameterRules() {
        ModelService modelService = mock(ModelService.class);
        ModelParameterRule temperature = ModelParameterRule.builder()
                .name("temperature")
                .label("Temperature")
                .type(ModelParameterRule.Type.FLOAT)
                .build();
        when(modelService.parameterRulesFor("model-1")).thenReturn(List.of(temperature));
        when(modelService.getModelProperties("model-1")).thenReturn(Map.of(
                "temperature", 0.7,
                "apiKey", "must-not-leak"));
        ModelCatalogQueryService queryService = new ModelCatalogQueryService(
                modelService,
                mock(ModelProviderRegistry.class),
                mock(ProviderCredentialService.class),
                mock(ProviderDefinitionService.class),
                mock(ProviderModelSettingsService.class));

        Map<String, Object> parameterView = queryService.parameters("model-1");

        assertThat(parameterView.get("parameters"))
                .isEqualTo(Map.of("temperature", 0.7));
    }

    /**
     * The "系统默认模型" dropdown must list only switched-on models and never the
     * same model twice — a materialized {@code agent_model} copy of a predefined
     * model (created by ModelService#findOrMaterialize on default resolution)
     * used to surface as a duplicate option.
     */
    @Test
    void groupedModelsByTypeListsOnlyEnabledModelsAndDedupesMaterializedRows() {
        ModelService modelService = mock(ModelService.class);
        ProviderCredentialService credentialService = mock(ProviderCredentialService.class);
        ProviderDefinitionService definitionService = mock(ProviderDefinitionService.class);
        ProviderModelSettingsService settingsService = mock(ProviderModelSettingsService.class);

        ProviderDefinitionEntity openai = new ProviderDefinitionEntity();
        openai.setName("openai");
        openai.setLabel("OpenAI");

        when(definitionService.list("t1")).thenReturn(List.of(openai));
        when(credentialService.findPrimary("t1", "openai"))
                .thenReturn(new ProviderCredentialEntity());

        // Switch state: gpt-4o ON; gpt-4o-mini has no setting row (= disabled).
        ProviderModelSettingEntity enabledSetting = new ProviderModelSettingEntity();
        enabledSetting.setEnabled(Boolean.TRUE);
        when(settingsService.settingIndex("t1", "openai"))
                .thenReturn(Map.of("gpt-4o::LLM", enabledSetting));

        when(definitionService.listPredefined("openai")).thenReturn(List.of(
                predefined("openai", "gpt-4o", ModelType.LLM),
                predefined("openai", "gpt-4o-mini", ModelType.LLM)));

        // Materialized copy of gpt-4o (enabled=true) must NOT re-add the model;
        // the switched-off custom row must stay hidden.
        when(modelService.listByProvider("t1", "openai")).thenReturn(List.of(
                agentModel("openai", "gpt-4o", ModelType.LLM, true),
                agentModel("openai", "my-custom-llm", ModelType.LLM, true),
                agentModel("openai", "switched-off", ModelType.LLM, false)));

        ModelCatalogQueryService queryService = new ModelCatalogQueryService(
                modelService,
                mock(ModelProviderRegistry.class),
                credentialService,
                definitionService,
                settingsService);

        Map<String, List<Map<String, Object>>> grouped =
                queryService.groupedModelsByType("t1");

        List<Map<String, Object>> llmProviders = grouped.get("LLM");
        assertThat(llmProviders).hasSize(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> modelList =
                (List<Map<String, Object>>) llmProviders.get(0).get("modelList");
        assertThat(modelList)
                .extracting(model -> model.get("modelName"))
                .containsExactly("gpt-4o", "my-custom-llm");
        // Types without candidates keep their empty buckets.
        assertThat(grouped.get("TEXT_EMBEDDING")).isEmpty();
    }

    /** Providers without a saved credential offer no candidates at all. */
    @Test
    void groupedModelsByTypeSkipsProvidersWithoutCredential() {
        ModelService modelService = mock(ModelService.class);
        ProviderCredentialService credentialService = mock(ProviderCredentialService.class);
        ProviderDefinitionService definitionService = mock(ProviderDefinitionService.class);
        ProviderModelSettingsService settingsService = mock(ProviderModelSettingsService.class);

        ProviderDefinitionEntity openai = new ProviderDefinitionEntity();
        openai.setName("openai");
        when(definitionService.list("t1")).thenReturn(List.of(openai));
        when(credentialService.findPrimary("t1", "openai")).thenReturn(null);

        ModelCatalogQueryService queryService = new ModelCatalogQueryService(
                modelService,
                mock(ModelProviderRegistry.class),
                credentialService,
                definitionService,
                settingsService);

        Map<String, List<Map<String, Object>>> grouped =
                queryService.groupedModelsByType("t1");

        assertThat(grouped.values()).allSatisfy(List::isEmpty);
    }

    /**
     * The catalog popover must show one row per model: a materialized
     * {@code agent_model} copy of a predefined model is dropped so users don't
     * see two entries (with two conflicting switches) for the same model.
     */
    @Test
    void catalogHidesMaterializedCopiesOfPredefinedModels() {
        ModelService modelService = mock(ModelService.class);
        ProviderDefinitionService definitionService = mock(ProviderDefinitionService.class);
        ProviderModelSettingsService settingsService = mock(ProviderModelSettingsService.class);

        ProviderModelSettingEntity enabledSetting = new ProviderModelSettingEntity();
        enabledSetting.setEnabled(Boolean.TRUE);
        when(settingsService.settingIndex("t1", "openai"))
                .thenReturn(Map.of("gpt-4o::LLM", enabledSetting));
        when(settingsService.listDefaults("t1")).thenReturn(Map.of());

        when(definitionService.listPredefined("openai")).thenReturn(List.of(
                predefined("openai", "gpt-4o", ModelType.LLM)));
        when(modelService.listByProvider("t1", "openai")).thenReturn(List.of(
                agentModel("openai", "gpt-4o", ModelType.LLM, true),
                agentModel("openai", "my-custom-llm", ModelType.LLM, true)));

        ModelCatalogQueryService queryService = new ModelCatalogQueryService(
                modelService,
                mock(ModelProviderRegistry.class),
                mock(ProviderCredentialService.class),
                definitionService,
                settingsService);

        List<Map<String, Object>> catalog = queryService.catalog("openai", "t1");

        assertThat(catalog)
                .extracting(row -> row.get("model"))
                .containsExactly("gpt-4o", "my-custom-llm");
        assertThat(catalog.get(0).get("source")).isEqualTo("predefined");
        assertThat(catalog.get(0).get("enabled")).isEqualTo(true);
        assertThat(catalog.get(1).get("source")).isEqualTo("custom");
    }

    private static PredefinedModelEntity predefined(String providerName, String model,
                                                    ModelType modelType) {
        PredefinedModelEntity entity = new PredefinedModelEntity();
        entity.setProviderName(providerName);
        entity.setModel(model);
        entity.setLabel(model);
        entity.setModelType(modelType);
        return entity;
    }

    private static ModelEntity agentModel(String providerName, String modelName,
                                          ModelType modelType, boolean enabled) {
        ModelEntity entity = new ModelEntity();
        entity.setProviderName(providerName);
        entity.setModelName(modelName);
        entity.setModelType(modelType);
        entity.setEnabled(enabled);
        return entity;
    }
}
