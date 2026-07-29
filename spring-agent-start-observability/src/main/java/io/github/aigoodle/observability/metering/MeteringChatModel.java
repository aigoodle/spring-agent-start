package io.github.aigoodle.observability.metering;

import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.observability.api.TokenUsage;
import io.github.aigoodle.observability.service.LlmMetricsService;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * A {@link ChatModel} decorator that times each call and records token usage / cost /
 * success into {@link LlmMetricsService}. Wraps the non-streaming {@code call(Prompt)}
 * path (which Spring AI's tool-calling loop uses), so every model round is captured.
 */
public class MeteringChatModel implements ChatModel {

    private final ChatModel delegate;
    private final String provider;
    private final String model;
    private final LlmMetricsService metrics;

    public MeteringChatModel(ChatModel delegate, String provider, String model, LlmMetricsService metrics) {
        this.delegate = delegate;
        this.provider = provider;
        this.model = model;
        this.metrics = metrics;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        long start = System.nanoTime();
        try {
            ChatResponse response = delegate.call(prompt);
            metrics.record(provider, model, null, extractUsage(response), elapsedMs(start), true, null);
            return response;
        } catch (RuntimeException e) {
            metrics.record(provider, model, null, TokenUsage.ZERO, elapsedMs(start), false,
                    e.getClass().getSimpleName());
            if (e instanceof AgentException) {
                throw e;
            }
            String raw = e.getMessage();
            String detail = (raw == null || raw.isBlank()) ? e.getClass().getSimpleName() : raw.strip();
            throw new AgentException("model_call_failed",
                    "Model call failed (provider=" + provider + ", model=" + model + "): " + detail, e);
        }
    }

    /**
     * Streaming path — needed by the agent chat SSE endpoint. Without an
     * override, Spring AI's default {@code stream()} throws
     * {@code UnsupportedOperationException("streaming is not supported")}
     * which broke typewriter output for wrapped models. We delegate + hook the
     * Flux lifecycle for metrics, aggregating token usage from the final chunk
     * (Spring AI populates {@code ChatResponse.metadata.usage} only on the
     * terminal frame during streaming).
     */
    @Override
    public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
        long start = System.nanoTime();
        java.util.concurrent.atomic.AtomicReference<TokenUsage> lastUsage =
                new java.util.concurrent.atomic.AtomicReference<>(TokenUsage.ZERO);
        return delegate.stream(prompt)
                .doOnNext(chunk -> {
                    TokenUsage u = extractUsage(chunk);
                    if (u != null && u != TokenUsage.ZERO) {
                        lastUsage.set(u);
                    }
                })
                .doOnComplete(() ->
                        metrics.record(provider, model, null, lastUsage.get(), elapsedMs(start), true, null))
                .doOnError(err -> metrics.record(provider, model, null, TokenUsage.ZERO,
                        elapsedMs(start), false, err.getClass().getSimpleName()));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static TokenUsage extractUsage(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return TokenUsage.ZERO;
        }
        ChatResponseMetadata metadata = response.getMetadata();
        Usage usage = metadata.getUsage();
        if (usage == null) {
            return TokenUsage.ZERO;
        }
        return TokenUsage.of(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
    }
}
