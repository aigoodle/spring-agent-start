package io.github.aigoodle.model.provider.builtin;

import io.github.aigoodle.model.enums.ModelType;
import io.github.aigoodle.model.provider.AbstractModelProvider;
import io.github.aigoodle.model.provider.CredentialField;
import io.github.aigoodle.model.provider.CredentialSchema;
import io.github.aigoodle.model.provider.ModelEndpoint;
import io.github.aigoodle.model.provider.PredefinedModel;
import io.github.aigoodle.model.provider.RemoteModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provider for any service exposing an OpenAI-compatible REST API. A single
 * implementation covers OpenAI itself plus DeepSeek, Zhipu, Moonshot, Qwen,
 * Volcengine Ark and most Chinese vendors — they differ only by base url and the
 * set of models they ship, which is why concrete presets are just instances of
 * this class with a different {@code name}/{@code defaultBaseUrl}.
 */
public class OpenAiCompatibleModelProvider extends AbstractModelProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleModelProvider.class);

    private final String name;
    private final String label;
    private final String defaultBaseUrl;
    private final List<PredefinedModel> predefinedModels;

    public OpenAiCompatibleModelProvider(String name, String label, String defaultBaseUrl,
                                         List<PredefinedModel> predefinedModels) {
        this.name = name;
        this.label = label;
        this.defaultBaseUrl = defaultBaseUrl;
        this.predefinedModels = predefinedModels == null ? List.of() : List.copyOf(predefinedModels);
    }

    public OpenAiCompatibleModelProvider(String name, String label, String defaultBaseUrl) {
        this(name, label, defaultBaseUrl, List.of());
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getLabel() {
        return label;
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
                        .required(false).defaultValue(defaultBaseUrl).placeholder(defaultBaseUrl).build()
        );
    }

    @Override
    public List<PredefinedModel> predefinedModels() {
        return predefinedModels;
    }

    private OpenAiApi buildApi(ModelEndpoint endpoint) {
        requireApiKey(endpoint);
        String baseUrl = endpoint.propertyOrDefault("baseUrl",
                endpoint.getBaseUrl() != null && !endpoint.getBaseUrl().isBlank()
                        ? endpoint.getBaseUrl() : defaultBaseUrl);
        // Boot 3.5's default RestClient picks up ReactorClientHttpRequestFactory
        // (reactor-netty is on the classpath via spring-boot-starter-webflux) and
        // that factory hard-codes {@code responseTimeout(Duration.ofSeconds(10))}
        // — turning any LLM call that needs >10s to first-byte into a
        // ReadTimeoutException. Real chat completions with tool calls routinely
        // exceed 10s (qwen3.6-flash, deepseek-reasoner, etc). Swap in a factory
        // with a 5-min ceiling so the ChatModel behaves like a normal SDK caller.
        // Override per-endpoint via the {@code readTimeoutSeconds} credential
        // property if a specific model needs more.
        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(chatRequestFactory(endpoint));
        OpenAiApi.Builder builder = OpenAiApi.builder()
                .apiKey(endpoint.getApiKey())
                .restClientBuilder(restClientBuilder);
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        // Spring AI's OpenAiApi defaults completionsPath="/v1/chat/completions"
        // and embeddingsPath="/v1/embeddings". Chinese "OpenAI-compat" vendors
        // (DashScope, Zhipu, Moonshot, Volcengine Ark, SiliconFlow, DeepSeek)
        // typically bake the "/v1" into the baseUrl itself
        // — e.g. https://dashscope.aliyuncs.com/compatible-mode/v1 — so the
        // untouched default sends requests to ".../v1/v1/chat/completions" and
        // DashScope answers with a slow-then-never response (real cause of the
        // "test connection" hang the user hit). Strip the "/v1" prefix from the
        // paths whenever the baseUrl already ends with "/vN".
        boolean baseUrlEndsInVersion = baseUrl != null
                && baseUrl.replaceAll("/+$", "").matches(".+/v\\d+");
        String completionsPath = endpoint.property("completionsPath");
        if (completionsPath != null && !completionsPath.isBlank()) {
            builder.completionsPath(completionsPath);
        } else if (baseUrlEndsInVersion) {
            builder.completionsPath("/chat/completions");
        }
        String embeddingsPath = endpoint.property("embeddingsPath");
        if (embeddingsPath != null && !embeddingsPath.isBlank()) {
            builder.embeddingsPath(embeddingsPath);
        } else if (baseUrlEndsInVersion) {
            builder.embeddingsPath("/embeddings");
        }
        return builder.build();
    }

    @Override
    public ChatModel createChatModel(ModelEndpoint endpoint) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(endpoint.getModelName());
        applyParameters(endpoint, options);
        return OpenAiChatModel.builder()
                .openAiApi(buildApi(endpoint))
                .defaultOptions(options.build())
                // Spring AI's default RetryTemplate is 10 attempts with
                // exponential backoff up to 3 min per retry — a single vendor
                // hiccup on a chat request can hang the user for 30+ min. Cap at
                // 3 attempts with 1s → 5s → 25s backoff so interactive agent
                // traffic degrades in seconds, not tens of minutes.
                .retryTemplate(org.springframework.retry.support.RetryTemplate.builder()
                        .maxAttempts(3)
                        .exponentialBackoff(java.time.Duration.ofSeconds(1),
                                5.0, java.time.Duration.ofSeconds(25))
                        .retryOn(org.springframework.ai.retry.TransientAiException.class)
                        .retryOn(org.springframework.web.client.ResourceAccessException.class)
                        .build())
                .build();
    }

    /** Pull optional model parameters from the DB-stored config (temperature, etc.). */
    private void applyParameters(ModelEndpoint endpoint, OpenAiChatOptions.Builder options) {
        Double temperature = doubleProperty(endpoint, "temperature");
        if (temperature != null) {
            options.temperature(temperature);
        }
        Double topP = doubleProperty(endpoint, "topP");
        if (topP != null) {
            options.topP(topP);
        }
        Integer maxTokens = endpoint.intProperty("maxTokens");
        if (maxTokens != null) {
            options.maxTokens(maxTokens);
        }
    }

    private static Double doubleProperty(ModelEndpoint endpoint, String key) {
        String v = endpoint.property(key);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public EmbeddingModel createEmbeddingModel(ModelEndpoint endpoint) {
        OpenAiEmbeddingOptions.Builder options = OpenAiEmbeddingOptions.builder()
                .model(endpoint.getModelName());
        Integer dimensions = endpoint.intProperty("dimensions");
        if (dimensions != null) {
            options.dimensions(dimensions);
        }
        return new OpenAiEmbeddingModel(buildApi(endpoint), MetadataMode.EMBED, options.build());
    }

    @Override
    public boolean supportsRemoteModelListing() {
        return true;
    }

    /**
     * Hit {@code GET {baseUrl}/models} and translate the OpenAI-shaped response into
     * {@link RemoteModel}s. When a returned id matches one of our presets we inherit
     * the preset's type/context/dimensions; otherwise we infer from the id itself.
     * <p>
     * Any HTTP or parse failure is surfaced by wrapping in {@link IllegalStateException}
     * so the UI can render a "拉取失败" hint alongside the vendor's own error message —
     * this is the primary "did my key work" signal, so we do <em>not</em> silently return
     * {@link #predefinedModels()} on error.
     */
    @Override
    public List<RemoteModel> listRemoteModels(ModelEndpoint endpoint) {
        requireApiKey(endpoint);
        String baseUrl = endpoint.propertyOrDefault("baseUrl",
                endpoint.getBaseUrl() != null && !endpoint.getBaseUrl().isBlank()
                        ? endpoint.getBaseUrl() : defaultBaseUrl);
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Provider '" + name + "' has no base url");
        }
        String url = resolveModelsUrl(baseUrl);

        RestClient client = RestClient.builder()
                .requestFactory(newRequestFactory())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();

        Map<String, Object> body;
        try {
            body = client.get()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + endpoint.getApiKey())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (Exception e) {
            log.warn("Remote model listing failed for provider={} url={}: {}", name, url, e.getMessage());
            throw new IllegalStateException("Failed to list models from " + url + ": " + e.getMessage(), e);
        }
        if (body == null) {
            return List.of();
        }

        Object data = body.get("data");
        List<Map<String, Object>> raw;
        if (data instanceof List<?> list) {
            raw = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> typed = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        typed.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    raw.add(typed);
                }
            }
        } else {
            return List.of();
        }

        Map<String, PredefinedModel> presetIndex = new HashMap<>();
        for (PredefinedModel p : predefinedModels) {
            presetIndex.put(p.getModel(), p);
        }

        List<RemoteModel> out = new ArrayList<>(raw.size());
        for (Map<String, Object> item : raw) {
            String id = str(item.get("id"));
            if (id == null || id.isBlank()) {
                continue;
            }
            String ownedBy = str(item.get("owned_by"));
            PredefinedModel preset = presetIndex.get(id);
            if (preset != null) {
                RemoteModel rm = RemoteModel.fromPredefined(preset);
                rm.setOwnedBy(ownedBy);
                out.add(rm);
            } else {
                ModelType type = RemoteModel.inferModelType(id);
                out.add(RemoteModel.builder()
                        .modelId(id).label(id).modelType(type)
                        .ownedBy(ownedBy).typeInferred(true).build());
            }
        }
        return out;
    }

    /**
     * Concatenate the {@code /models} path onto a base url without double-slashes,
     * and honour caller-provided overrides. Most OpenAI-compat vendors serve the
     * listing at {@code {base}/models}, but some (Ollama, ZhipuAI's official SDK) use
     * different paths — support a {@code modelsPath} property for those edge cases.
     */
    private static String resolveModelsUrl(String baseUrl) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        // OpenAI's official base is https://api.openai.com — no /v1 — normalise both.
        if (!trimmed.contains("/v")) {
            return trimmed + "/v1/models";
        }
        return trimmed + "/models";
    }

    private static org.springframework.http.client.SimpleClientHttpRequestFactory newRequestFactory() {
        org.springframework.http.client.SimpleClientHttpRequestFactory f =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        f.setReadTimeout((int) Duration.ofSeconds(20).toMillis());
        return f;
    }

    /**
     * Request factory used by Spring AI's OpenAiApi for chat / embedding calls.
     * Reactor's default 10s response timeout is too short for real LLM latency,
     * so we swap in {@link org.springframework.http.client.JdkClientHttpRequestFactory}
     * (JDK 11+ built-in HttpClient) and set an explicit multi-minute read timeout.
     * Callers can override via a {@code readTimeoutSeconds} credential property
     * for models with unusually long time-to-first-byte.
     */
    private static org.springframework.http.client.ClientHttpRequestFactory chatRequestFactory(
            ModelEndpoint endpoint) {
        Integer configured = endpoint.intProperty("readTimeoutSeconds");
        Duration readTimeout = Duration.ofSeconds(configured != null && configured > 0 ? configured : 300);
        org.springframework.http.client.JdkClientHttpRequestFactory f =
                new org.springframework.http.client.JdkClientHttpRequestFactory();
        f.setReadTimeout(readTimeout);
        return f;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
