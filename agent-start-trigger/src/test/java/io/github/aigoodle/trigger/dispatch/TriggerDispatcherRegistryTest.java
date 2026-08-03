package io.github.aigoodle.trigger.dispatch;

import io.github.aigoodle.common.exception.AgentException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TriggerDispatcherRegistryTest {

    @Test
    void resolvesTargetTypesIndependentlyOfSystemLocale() {
        Locale originalLocale = Locale.getDefault();
        TriggerDispatcher pipelineDispatcher = dispatcher("PIPELINE");
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            TriggerDispatcherRegistry registry = new TriggerDispatcherRegistry(
                    List.of(pipelineDispatcher));

            assertThat(registry.get("pipeline")).isSameAs(pipelineDispatcher);
        } finally {
            Locale.setDefault(originalLocale);
        }
    }

    @Test
    void reportsResolvedDefaultTypeWhenDispatcherIsMissing() {
        TriggerDispatcherRegistry registry = new TriggerDispatcherRegistry(List.of());

        assertThatThrownBy(() -> registry.get(null))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("workflow");
    }

    private static TriggerDispatcher dispatcher(String targetType) {
        return new TriggerDispatcher() {
            @Override
            public String targetType() {
                return targetType;
            }

            @Override
            public DispatchResult dispatch(String targetId, Map<String, Object> inputs,
                                           String conversationId) {
                return DispatchResult.ok(conversationId, inputs);
            }
        };
    }
}
