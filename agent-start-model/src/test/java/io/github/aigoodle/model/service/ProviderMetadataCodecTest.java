package io.github.aigoodle.model.service;

import io.github.aigoodle.model.entity.PredefinedModelEntity;
import io.github.aigoodle.model.entity.ProviderDefinitionEntity;
import io.github.aigoodle.model.enums.ModelFeature;
import io.github.aigoodle.model.enums.ModelType;
import io.github.aigoodle.model.provider.PredefinedModel;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderMetadataCodecTest {

    @Test
    void ignoresUnknownModelTypesDuringRollingUpgrades() {
        assertThat(ProviderMetadataCodec.modelTypes("[\"llm\",\"future_type\"]"))
                .containsExactly(ModelType.LLM);
    }

    @Test
    void convertsInMemoryModelUsingReadableDefaults() {
        PredefinedModel model = PredefinedModel.builder()
                .model("gpt-readable")
                .modelType(ModelType.LLM)
                .features(Set.of(ModelFeature.STREAM))
                .build();

        PredefinedModelEntity entity = ProviderMetadataCodec.toEntity("openai", model, 7, "system");

        assertThat(entity.getLabel()).isEqualTo("gpt-readable");
        assertThat(entity.getProviderName()).isEqualTo("openai");
        assertThat(entity.getFeatures()).contains("STREAM");
        assertThat(entity.getSortOrder()).isEqualTo(7);
    }

    @Test
    void patchChangesOnlyFieldsPresentInTheRequest() {
        ProviderDefinitionEntity definition = new ProviderDefinitionEntity();
        definition.setLabel("Original");
        definition.setEnabled(true);

        ProviderDefinitionPatch.apply(definition, Map.of("label", "Readable"));

        assertThat(definition.getLabel()).isEqualTo("Readable");
        assertThat(definition.getEnabled()).isTrue();
    }
}
