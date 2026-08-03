package io.github.aigoodle.observability.metering;

import io.github.aigoodle.observability.api.LlmCallMeasurement;
import io.github.aigoodle.observability.service.LlmMetricsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeteringChatModelTest {

    @Test
    void recordsEachStreamSubscriptionIndependently() {
        ChatModel delegate = mock(ChatModel.class);
        LlmMetricsService metricsService = mock(LlmMetricsService.class);
        Prompt prompt = mock(Prompt.class);
        when(delegate.stream(prompt)).thenReturn(Flux.empty());
        MeteringChatModel meteredModel = new MeteringChatModel(
                delegate, "openai", "gpt-4o", metricsService);

        Flux<?> responseStream = meteredModel.stream(prompt);
        responseStream.blockLast();
        responseStream.blockLast();

        verify(delegate, times(2)).stream(prompt);
        verify(metricsService, times(2)).record(any(LlmCallMeasurement.class));
    }

    @Test
    void recordsCancelledStreamAsFailure() {
        ChatModel delegate = mock(ChatModel.class);
        LlmMetricsService metricsService = mock(LlmMetricsService.class);
        Prompt prompt = mock(Prompt.class);
        when(delegate.stream(prompt)).thenReturn(Flux.never());
        MeteringChatModel meteredModel = new MeteringChatModel(
                delegate, "openai", "gpt-4o", metricsService);

        Disposable subscription = meteredModel.stream(prompt).subscribe();
        subscription.dispose();

        ArgumentCaptor<LlmCallMeasurement> measurementCaptor =
                ArgumentCaptor.forClass(LlmCallMeasurement.class);
        verify(metricsService).record(measurementCaptor.capture());
        assertThat(measurementCaptor.getValue().successful()).isFalse();
        assertThat(measurementCaptor.getValue().errorType()).isEqualTo("CancellationException");
    }
}
