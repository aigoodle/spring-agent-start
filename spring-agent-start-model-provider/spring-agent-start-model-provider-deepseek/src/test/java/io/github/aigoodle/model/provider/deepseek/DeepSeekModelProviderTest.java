package io.github.aigoodle.model.provider.deepseek;

import io.github.aigoodle.model.enums.ModelType;
import io.github.aigoodle.model.provider.ModelEndpoint;
import io.github.aigoodle.model.provider.builtin.BuiltinModelProviders;
import io.github.aigoodle.model.registry.ModelProviderRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeepSeekModelProviderTest {

    @Test
    void registeredUnderNameDeepseek() {
        DeepSeekModelProvider provider = new DeepSeekModelProvider();
        assertEquals("deepseek", provider.getName());
        assertTrue(provider.supports(ModelType.LLM));
        assertFalse(provider.supports(ModelType.TEXT_EMBEDDING),
                "DeepSeek SDK does not ship an embedding model");
    }

    @Test
    void overridesBuiltinDeepseekInRegistry() {
        DeepSeekModelProvider native_ = new DeepSeekModelProvider();
        List<io.github.aigoodle.model.provider.ModelProvider> all = new ArrayList<>();
        all.add(native_);
        all.addAll(BuiltinModelProviders.openAiCompatible());
        ModelProviderRegistry registry = new ModelProviderRegistry(all);
        assertSame(native_, registry.get("deepseek"));
    }

    @Test
    void buildsChatModelWithoutNetwork() {
        DeepSeekModelProvider provider = new DeepSeekModelProvider();
        ModelEndpoint endpoint = ModelEndpoint.builder()
                .id("d1").providerName("deepseek").modelName("deepseek-chat")
                .modelType(ModelType.LLM).apiKey("sk-test").build();
        assertNotNull(provider.createChatModel(endpoint));
    }

    @Test
    void embeddingIsUnsupported() {
        DeepSeekModelProvider provider = new DeepSeekModelProvider();
        ModelEndpoint endpoint = ModelEndpoint.builder()
                .id("d2").providerName("deepseek").modelName("does-not-matter")
                .modelType(ModelType.TEXT_EMBEDDING).apiKey("sk-test").build();
        assertThrows(UnsupportedOperationException.class,
                () -> provider.createEmbeddingModel(endpoint));
    }
}
