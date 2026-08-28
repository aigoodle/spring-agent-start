package io.github.aigoodle.model.service;

import io.github.aigoodle.model.entity.PredefinedModelEntity;
import io.github.aigoodle.model.entity.ProviderDefinitionEntity;
import io.github.aigoodle.model.enums.ModelType;
import io.github.aigoodle.model.provider.ModelProvider;
import io.github.aigoodle.model.provider.PredefinedModel;
import io.github.aigoodle.model.registry.ModelProviderRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderDefinitionSeederTest {

    @Test
    void establishedCatalogIsNotRewrittenOnRestart() {
        ModelProvider provider = provider();
        ProviderDefinitionService service = mock(ProviderDefinitionService.class);
        ProviderDefinitionEntity definition = new ProviderDefinitionEntity();
        definition.setName("test-provider");
        PredefinedModelEntity predefined = new PredefinedModelEntity();
        predefined.setProviderName("test-provider");
        predefined.setModel("test-model");
        predefined.setModelType(ModelType.LLM);
        when(service.listOwnedDefinitions("system")).thenReturn(List.of(definition));
        when(service.listOwnedPredefined("system")).thenReturn(List.of(predefined));

        int inserted = new ProviderDefinitionSeeder(
                new ModelProviderRegistry(List.of(provider)), service).seed();

        assertThat(inserted).isZero();
        verify(service, never()).upsert(any());
        verify(service, never()).upsertPredefined(any());
    }

    @Test
    void missingCatalogRowsAreInserted() {
        ModelProvider provider = provider();
        ProviderDefinitionService service = mock(ProviderDefinitionService.class);
        when(service.fromMemory(any(), any(), any(Integer.class)))
                .thenReturn(new PredefinedModelEntity());

        int inserted = new ProviderDefinitionSeeder(
                new ModelProviderRegistry(List.of(provider)), service).seed();

        assertThat(inserted).isOne();
        verify(service).upsert(any(ProviderDefinitionEntity.class));
        verify(service).upsertPredefined(any(PredefinedModelEntity.class));
    }

    private static ModelProvider provider() {
        ModelProvider provider = mock(ModelProvider.class);
        when(provider.getName()).thenReturn("test-provider");
        when(provider.getLabel()).thenReturn("Test Provider");
        when(provider.implementationKey()).thenReturn("test-provider");
        when(provider.supportedModelTypes()).thenReturn(Set.of(ModelType.LLM));
        when(provider.predefinedModels()).thenReturn(List.of(PredefinedModel.builder()
                .model("test-model")
                .modelType(ModelType.LLM)
                .build()));
        return provider;
    }
}
