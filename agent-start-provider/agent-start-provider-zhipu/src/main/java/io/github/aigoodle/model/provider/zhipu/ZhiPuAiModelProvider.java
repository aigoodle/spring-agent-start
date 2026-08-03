package io.github.aigoodle.model.provider.zhipu;

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
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatOptions;
import org.springframework.ai.zhipuai.ZhiPuAiEmbeddingModel;
import org.springframework.ai.zhipuai.ZhiPuAiEmbeddingOptions;
import org.springframework.ai.zhipuai.api.ZhiPuAiApi;

import java.util.List;
import java.util.Set;

/**
 * Native Zhipu AI provider. Registered under name {@code "zhipu"} so it wins over
 * the built-in OpenAI-compatible preset with the same name (see
 * {@link io.github.aigoodle.model.registry.ModelProviderRegistry} — app beans
 * take precedence).
 */
public class ZhiPuAiModelProvider extends AbstractModelProvider {

    public static final String NAME = "zhipu";
    public static final String DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4";

    private static final Set<ModelFeature> CHAT_FEATURES =
            Set.of(ModelFeature.STREAM, ModelFeature.TOOL_CALL, ModelFeature.STREAM_TOOL_CALL);

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getLabel() {
        return "Zhipu AI (GLM)";
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
                        .name("baseUrl").label("Base URL").type(CredentialField.Type.TEXT)
                        .required(false).defaultValue(DEFAULT_BASE_URL).placeholder(DEFAULT_BASE_URL).build()
        );
    }

    @Override
    public List<PredefinedModel> predefinedModels() {
        return List.of(
                llm("glm-4-plus", 128_000),
                llm("glm-4-air", 128_000),
                llm("glm-4-flash", 128_000),
                llm("glm-4v-plus", 8_000),      // vision
                llm("glm-4v", 8_000),           // vision
                embedding("embedding-3", 2048),
                embedding("embedding-2", 1024)
        );
    }

    @Override
    public ChatModel createChatModel(ModelEndpoint endpoint) {
        requireApiKey(endpoint);
        ZhiPuAiApi api = buildApi(endpoint);
        ZhiPuAiChatOptions.Builder options = ZhiPuAiChatOptions.builder().model(endpoint.getModelName());
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
        return new ZhiPuAiChatModel(api, options.build());
    }

    @Override
    public EmbeddingModel createEmbeddingModel(ModelEndpoint endpoint) {
        requireApiKey(endpoint);
        ZhiPuAiApi api = buildApi(endpoint);
        ZhiPuAiEmbeddingOptions.Builder options = ZhiPuAiEmbeddingOptions.builder()
                .model(endpoint.getModelName());
        Integer dimensions = endpoint.intProperty("dimensions");
        if (dimensions != null) {
            options.dimensions(dimensions);
        }
        return new ZhiPuAiEmbeddingModel(api, MetadataMode.EMBED, options.build());
    }

    private static ZhiPuAiApi buildApi(ModelEndpoint endpoint) {
        String baseUrl = endpoint.resolveBaseUrl(DEFAULT_BASE_URL);
        ZhiPuAiApi.Builder builder = ZhiPuAiApi.builder().apiKey(endpoint.getApiKey());
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
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
