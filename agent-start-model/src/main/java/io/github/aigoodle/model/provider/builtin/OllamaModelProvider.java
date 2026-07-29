package io.github.aigoodle.model.provider.builtin;

import io.github.aigoodle.model.enums.ModelType;
import io.github.aigoodle.model.provider.AbstractModelProvider;
import io.github.aigoodle.model.provider.CredentialField;
import io.github.aigoodle.model.provider.CredentialSchema;
import io.github.aigoodle.model.provider.ModelEndpoint;
import io.github.aigoodle.model.provider.RemoteModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provider for a local or remote <a href="https://ollama.com">Ollama</a> server.
 * No api key is required; only the server base url (defaults to localhost:11434).
 */
public class OllamaModelProvider extends AbstractModelProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaModelProvider.class);

    public static final String NAME = "ollama";
    private static final String DEFAULT_BASE_URL = "http://localhost:11434";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getLabel() {
        return "Ollama";
    }

    @Override
    public Set<ModelType> supportedModelTypes() {
        return Set.of(ModelType.LLM, ModelType.TEXT_EMBEDDING);
    }

    @Override
    public CredentialSchema credentialSchema() {
        return CredentialSchema.of(
                CredentialField.builder()
                        .name("baseUrl").label("Base URL").type(CredentialField.Type.TEXT)
                        .required(false).defaultValue(DEFAULT_BASE_URL).placeholder(DEFAULT_BASE_URL).build()
        );
    }

    private OllamaApi buildApi(ModelEndpoint endpoint) {
        String baseUrl = endpoint.propertyOrDefault("baseUrl",
                endpoint.getBaseUrl() != null && !endpoint.getBaseUrl().isBlank()
                        ? endpoint.getBaseUrl() : DEFAULT_BASE_URL);
        return OllamaApi.builder().baseUrl(baseUrl).build();
    }

    @Override
    public ChatModel createChatModel(ModelEndpoint endpoint) {
        OllamaChatOptions.Builder options = OllamaChatOptions.builder().model(endpoint.getModelName());
        String temperature = endpoint.property("temperature");
        if (temperature != null && !temperature.isBlank()) {
            try {
                options.temperature(Double.valueOf(temperature));
            } catch (NumberFormatException ignored) {
                // leave default
            }
        }
        return OllamaChatModel.builder()
                .ollamaApi(buildApi(endpoint))
                .defaultOptions(options.build())
                .build();
    }

    @Override
    public EmbeddingModel createEmbeddingModel(ModelEndpoint endpoint) {
        OllamaEmbeddingOptions options = OllamaEmbeddingOptions.builder()
                .model(endpoint.getModelName())
                .build();
        return OllamaEmbeddingModel.builder()
                .ollamaApi(buildApi(endpoint))
                .defaultOptions(options)
                .build();
    }

    @Override
    public boolean supportsRemoteModelListing() {
        return true;
    }

    /**
     * Ollama returns {@code {models:[{name, size, digest, ...}]}} at
     * {@code GET /api/tags}. We emit each installed model once with the inferred type;
     * users who want to alias a chat model as embedding (Ollama does support it at
     * runtime) can add the alternate entry via "手动添加".
     */
    @Override
    public List<RemoteModel> listRemoteModels(ModelEndpoint endpoint) {
        String baseUrl = endpoint.propertyOrDefault("baseUrl",
                endpoint.getBaseUrl() != null && !endpoint.getBaseUrl().isBlank()
                        ? endpoint.getBaseUrl() : DEFAULT_BASE_URL);
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String url = trimmed + "/api/tags";

        RestClient client = RestClient.builder()
                .requestFactory(newRequestFactory())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();

        Map<String, Object> body;
        try {
            body = client.get().uri(url).retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (Exception e) {
            log.warn("Ollama listing failed at {}: {}", url, e.getMessage());
            throw new IllegalStateException("Failed to list Ollama models from " + url + ": " + e.getMessage(), e);
        }
        if (body == null) {
            return List.of();
        }
        Object models = body.get("models");
        if (!(models instanceof List<?> list)) {
            return List.of();
        }
        List<RemoteModel> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            Object nameVal = m.get("name");
            if (nameVal == null) {
                continue;
            }
            String modelId = String.valueOf(nameVal);
            ModelType inferred = RemoteModel.inferModelType(modelId);
            out.add(RemoteModel.builder()
                    .modelId(modelId).label(modelId).modelType(inferred)
                    .ownedBy("ollama").typeInferred(true).build());
        }
        return out;
    }

    private static org.springframework.http.client.SimpleClientHttpRequestFactory newRequestFactory() {
        org.springframework.http.client.SimpleClientHttpRequestFactory f =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        f.setReadTimeout((int) Duration.ofSeconds(15).toMillis());
        return f;
    }
}
