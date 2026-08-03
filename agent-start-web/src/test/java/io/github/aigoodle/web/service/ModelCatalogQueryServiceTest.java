package io.github.aigoodle.web.service;

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
}
