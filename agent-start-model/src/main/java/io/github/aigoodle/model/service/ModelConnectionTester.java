package io.github.aigoodle.model.service;

import io.github.aigoodle.model.entity.ModelEntity;
import io.github.aigoodle.model.enums.ModelType;
import io.github.aigoodle.model.provider.ModelEndpoint;
import io.github.aigoodle.model.registry.ModelProviderRegistry;
import io.github.aigoodle.model.runtime.ModelInstanceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.retry.support.RetryTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Executes the bounded, user-facing connectivity check for a configured model. */
public class ModelConnectionTester {

    private static final Logger logger = LoggerFactory.getLogger(ModelConnectionTester.class);
    private static final int MAX_CHAT_SNIPPET_LENGTH = 200;

    private final ModelProviderRegistry providerRegistry;
    private final ModelInstanceFactory instanceFactory;

    public ModelConnectionTester(ModelProviderRegistry providerRegistry,
                                 ModelInstanceFactory instanceFactory) {
        this.providerRegistry = providerRegistry;
        this.instanceFactory = instanceFactory;
    }

    public Map<String, Object> test(ModelEntity model, ModelEndpoint endpoint) {
        long startedAtNanos = System.nanoTime();
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            addSuccessfulProbe(result, endpoint);
            result.put("ok", true);
        } catch (RuntimeException connectionFailure) {
            logger.warn("Model {} connection test failed: {}",
                    model.getId(), connectionFailure.getMessage());
            result.put("ok", false);
            result.put("error", connectionFailure.getMessage());
        }
        result.put("latencyMs", elapsedMilliseconds(startedAtNanos));
        return result;
    }

    private void addSuccessfulProbe(Map<String, Object> result, ModelEndpoint endpoint) {
        if (endpoint.getModelType() == ModelType.LLM) {
            result.put("kind", "chat");
            result.put("snippet", chatSnippet(endpoint));
            return;
        }
        if (endpoint.getModelType() == ModelType.TEXT_EMBEDDING) {
            result.put("kind", "embedding");
            result.put("dimensions", embeddingDimensions(endpoint));
            return;
        }
        result.put("kind", "unsupported");
        result.put("message", "Test not supported for " + endpoint.getModelType());
    }

    private String chatSnippet(ModelEndpoint endpoint) {
        String reply = ChatClient.builder(fastFailChatModel(endpoint)).build()
                .prompt().user("ping").call().content();
        if (reply == null) {
            return "";
        }
        return reply.substring(0, Math.min(MAX_CHAT_SNIPPET_LENGTH, reply.length()));
    }

    private int embeddingDimensions(ModelEndpoint endpoint) {
        EmbeddingModel embeddingModel = instanceFactory.getOrCreate(endpoint).getEmbeddingModel();
        float[] embeddingVector = embeddingModel.embed("connection test");
        return embeddingVector == null ? 0 : embeddingVector.length;
    }

    private ChatModel fastFailChatModel(ModelEndpoint endpoint) {
        ChatModel chatModel = providerRegistry.get(endpoint.getProviderName())
                .createChatModel(endpoint);
        if (chatModel instanceof OpenAiChatModel openAiChatModel) {
            return openAiChatModel.mutate()
                    .retryTemplate(RetryTemplate.builder()
                            .maxAttempts(2)
                            .fixedBackoff(Duration.ofMillis(500))
                            .build())
                    .build();
        }
        return chatModel;
    }

    private static long elapsedMilliseconds(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
