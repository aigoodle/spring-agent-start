package io.github.aigoodle.model.provider.builtin;

import io.github.aigoodle.model.enums.ModelType;
import io.github.aigoodle.model.provider.PredefinedModel;
import io.github.aigoodle.model.provider.RemoteModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleProviderSupportTest {

    @Test
    void resolvesDefaultAndProviderSpecificModelCatalogUrls() {
        assertThat(OpenAiCompatibleModelProvider.resolveModelsUrl("https://api.openai.com", null))
                .isEqualTo("https://api.openai.com/v1/models");
        assertThat(OpenAiCompatibleModelProvider.resolveModelsUrl("https://vendor.example/v1/", null))
                .isEqualTo("https://vendor.example/v1/models");
        assertThat(OpenAiCompatibleModelProvider.resolveModelsUrl(
                "https://vendor.example/v1/", "catalog/models"))
                .isEqualTo("https://vendor.example/v1/catalog/models");
    }

    @Test
    void mapsKnownModelsFromPresetsAndInfersUnknownModels() {
        PredefinedModel knownModel = PredefinedModel.builder()
                .model("chat-pro")
                .label("Chat Pro")
                .modelType(ModelType.LLM)
                .contextLength(32_000)
                .build();
        RemoteModelCatalogMapper mapper = new RemoteModelCatalogMapper(List.of(knownModel));

        List<RemoteModel> models = mapper.fromResponse(Map.of("data", List.of(
                Map.of("id", "chat-pro", "owned_by", "vendor"),
                Map.of("id", "text-embedding-next"),
                Map.of("owned_by", "invalid"),
                "unexpected-entry")));

        assertThat(models).hasSize(2);
        assertThat(models.get(0))
                .extracting(RemoteModel::getModelId, RemoteModel::getLabel,
                        RemoteModel::getModelType, RemoteModel::getContextLength,
                        RemoteModel::getOwnedBy, RemoteModel::isTypeInferred)
                .containsExactly("chat-pro", "Chat Pro", ModelType.LLM, 32_000, "vendor", false);
        assertThat(models.get(1))
                .extracting(RemoteModel::getModelId, RemoteModel::getModelType,
                        RemoteModel::isTypeInferred)
                .containsExactly("text-embedding-next", ModelType.TEXT_EMBEDDING, true);
    }

    @Test
    void treatsMissingCatalogDataAsAnEmptyResult() {
        RemoteModelCatalogMapper mapper = new RemoteModelCatalogMapper(List.of());

        assertThat(mapper.fromResponse(null)).isEmpty();
        assertThat(mapper.fromResponse(Map.of("data", "not-a-list"))).isEmpty();
    }
}
