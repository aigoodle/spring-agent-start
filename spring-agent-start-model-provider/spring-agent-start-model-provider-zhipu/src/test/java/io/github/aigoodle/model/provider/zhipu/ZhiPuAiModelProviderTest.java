package io.github.aigoodle.model.provider.zhipu;

import io.github.aigoodle.model.enums.ModelType;
import io.github.aigoodle.model.provider.ModelEndpoint;
import io.github.aigoodle.model.provider.builtin.BuiltinModelProviders;
import io.github.aigoodle.model.registry.ModelProviderRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Object-only construction — validates registry override and that the native SDK
 * builder path compiles and runs. No network call.
 */
class ZhiPuAiModelProviderTest {

    @Test
    void registeredUnderNameZhipu() {
        ZhiPuAiModelProvider provider = new ZhiPuAiModelProvider();
        assertEquals("zhipu", provider.getName());
        assertTrue(provider.supports(ModelType.LLM));
        assertTrue(provider.supports(ModelType.TEXT_EMBEDDING));
    }

    @Test
    void overridesBuiltinZhipuInRegistry() {
        ZhiPuAiModelProvider native_ = new ZhiPuAiModelProvider();
        List<io.github.aigoodle.model.provider.ModelProvider> all = new ArrayList<>();
        all.add(native_);                                       // starter bean first
        all.addAll(BuiltinModelProviders.openAiCompatible());   // built-ins after
        ModelProviderRegistry registry = new ModelProviderRegistry(all);
        assertSame(native_, registry.get("zhipu"));
    }

    @Test
    void buildsChatModelWithoutNetwork() {
        ZhiPuAiModelProvider provider = new ZhiPuAiModelProvider();
        ModelEndpoint endpoint = ModelEndpoint.builder()
                .id("z1").providerName("zhipu").modelName("glm-4-flash")
                .modelType(ModelType.LLM).apiKey("sk-test").build();
        assertNotNull(provider.createChatModel(endpoint));
    }

    @Test
    void buildsEmbeddingModelWithoutNetwork() {
        ZhiPuAiModelProvider provider = new ZhiPuAiModelProvider();
        ModelEndpoint endpoint = ModelEndpoint.builder()
                .id("z2").providerName("zhipu").modelName("embedding-3")
                .modelType(ModelType.TEXT_EMBEDDING).apiKey("sk-test").build();
        assertNotNull(provider.createEmbeddingModel(endpoint));
    }

    @Test
    void missingApiKeyThrows() {
        ZhiPuAiModelProvider provider = new ZhiPuAiModelProvider();
        ModelEndpoint endpoint = ModelEndpoint.builder()
                .id("z3").providerName("zhipu").modelName("glm-4-flash")
                .modelType(ModelType.LLM).build();
        assertThrows(IllegalArgumentException.class, () -> provider.createChatModel(endpoint));
    }
}
