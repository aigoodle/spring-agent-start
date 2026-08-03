package io.github.aigoodle.model.provider.volcengine;

import io.github.aigoodle.model.enums.ModelFeature;
import io.github.aigoodle.model.enums.ModelType;
import io.github.aigoodle.model.provider.AbstractModelProvider;
import io.github.aigoodle.model.provider.CredentialField;
import io.github.aigoodle.model.provider.CredentialSchema;
import io.github.aigoodle.model.provider.ModelEndpoint;
import io.github.aigoodle.model.provider.PredefinedModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.List;
import java.util.Set;

/**
 * Volcengine Ark provider (Doubao and other models hosted on Volcengine). The
 * public API is OpenAI-compatible so we use spring-ai-openai as the transport, but
 * we take over the built-in {@code "volcengine"} preset to:
 * <ul>
 *   <li>expose an explicit {@code endpointId} credential field — Volcengine
 *       identifies an inference deployment by {@code ep-xxx} rather than by model
 *       name — and treat it as the model when set;</li>
 *   <li>ship the current Doubao line-up as predefined models.</li>
 * </ul>
 */
public class VolcengineArkModelProvider extends AbstractModelProvider {

    public static final String NAME = "volcengine";
    public static final String DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3";

    private static final Set<ModelFeature> CHAT_FEATURES =
            Set.of(ModelFeature.STREAM, ModelFeature.TOOL_CALL, ModelFeature.STREAM_TOOL_CALL);

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getLabel() {
        return "Volcengine Ark (Doubao)";
    }

    @Override
    public Set<ModelType> supportedModelTypes() {
        return Set.of(ModelType.LLM, ModelType.TEXT_EMBEDDING);
    }

    @Override
    public CredentialSchema credentialSchema() {
        return CredentialSchema.of(
                CredentialField.secret("apiKey", "API Key", true),
                CredentialField.builder()
                        .name("endpointId").label("Endpoint ID (ep-xxx)").type(CredentialField.Type.TEXT)
                        .required(false)
                        .placeholder("ep-20240611-xxxx (overrides model name when set)")
                        .build(),
                CredentialField.builder()
                        .name("baseUrl").label("Base URL").type(CredentialField.Type.TEXT)
                        .required(false).defaultValue(DEFAULT_BASE_URL).placeholder(DEFAULT_BASE_URL).build()
        );
    }

    @Override
    public List<PredefinedModel> predefinedModels() {
        // Volcengine deployments always route through an ep-xxx endpoint id, so these
        // are hints for what to point that endpoint at rather than callable model names.
        return List.of(
                llm("doubao-1-5-pro-32k", 32_000),
                llm("doubao-1-5-pro-256k", 256_000),
                llm("doubao-1-5-lite-32k", 32_000),
                llm("doubao-vision-lite-32k", 32_000),
                embedding("doubao-embedding", 2560)
        );
    }

    @Override
    public ChatModel createChatModel(ModelEndpoint endpoint) {
        requireApiKey(endpoint);
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(effectiveModel(endpoint));
        Double temperature = endpoint.decimalProperty("temperature");
        if (temperature != null) {
            options.temperature(temperature);
        }
        Double topP = endpoint.decimalProperty("topP");
        if (topP != null) {
            options.topP(topP);
        }
        Integer maxTokens = endpoint.intProperty("maxTokens");
        if (maxTokens != null) {
            options.maxTokens(maxTokens);
        }
        return OpenAiChatModel.builder()
                .openAiApi(buildApi(endpoint))
                .defaultOptions(options.build())
                .build();
    }

    @Override
    public EmbeddingModel createEmbeddingModel(ModelEndpoint endpoint) {
        requireApiKey(endpoint);
        OpenAiEmbeddingOptions.Builder options = OpenAiEmbeddingOptions.builder()
                .model(effectiveModel(endpoint));
        Integer dimensions = endpoint.intProperty("dimensions");
        if (dimensions != null) {
            options.dimensions(dimensions);
        }
        return new OpenAiEmbeddingModel(buildApi(endpoint), MetadataMode.EMBED, options.build());
    }

    private static OpenAiApi buildApi(ModelEndpoint endpoint) {
        String baseUrl = endpoint.resolveBaseUrl(DEFAULT_BASE_URL);
        return OpenAiApi.builder()
                .apiKey(endpoint.getApiKey())
                .baseUrl(baseUrl)
                .build();
    }

    /** Endpoint id trumps the model name when set — that is how Ark identifies models. */
    private static String effectiveModel(ModelEndpoint endpoint) {
        String endpointId = endpoint.property("endpointId");
        if (endpointId != null && !endpointId.isBlank()) {
            return endpointId;
        }
        return endpoint.getModelName();
    }

    private static PredefinedModel llm(String model, int ctx) {
        return PredefinedModel.builder().model(model).label(model).modelType(ModelType.LLM)
                .features(CHAT_FEATURES).contextLength(ctx).build();
    }

    private static PredefinedModel embedding(String model, int dim) {
        return PredefinedModel.builder().model(model).label(model).modelType(ModelType.TEXT_EMBEDDING)
                .dimensions(dim).build();
    }

}
