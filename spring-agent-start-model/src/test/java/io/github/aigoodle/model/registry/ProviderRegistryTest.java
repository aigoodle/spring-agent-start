package io.github.aigoodle.model.registry;

import io.github.aigoodle.model.enums.ModelType;
import io.github.aigoodle.model.provider.ModelEndpoint;
import io.github.aigoodle.model.provider.builtin.BuiltinModelProviders;
import io.github.aigoodle.model.provider.builtin.OllamaModelProvider;
import io.github.aigoodle.model.runtime.ModelInstance;
import io.github.aigoodle.model.runtime.ModelInstanceFactory;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies provider discovery and that the factory can build real Spring AI model
 * objects (object construction only — no network call).
 */
class ProviderRegistryTest {

    private ModelProviderRegistry registry() {
        List<io.github.aigoodle.model.provider.ModelProvider> all = new ArrayList<>();
        all.add(new OllamaModelProvider());
        all.addAll(BuiltinModelProviders.openAiCompatible());
        return new ModelProviderRegistry(all);
    }

    @Test
    void builtinProvidersAreRegistered() {
        ModelProviderRegistry registry = registry();
        assertTrue(registry.contains("openai"));
        assertTrue(registry.contains("deepseek"));
        assertTrue(registry.contains("zhipu"));
        assertTrue(registry.contains("ollama"));
        assertTrue(registry.get("openai").supports(ModelType.LLM));
        assertTrue(registry.get("openai").supports(ModelType.TEXT_EMBEDDING));
    }

    @Test
    void unknownProviderThrows() {
        assertThrows(RuntimeException.class, () -> registry().get("does-not-exist"));
    }

    @Test
    void factoryBuildsOpenAiChatModel() {
        ModelInstanceFactory factory = new ModelInstanceFactory(registry());
        ModelEndpoint endpoint = ModelEndpoint.builder()
                .id("m1").providerName("openai").modelName("gpt-4o-mini")
                .modelType(ModelType.LLM).apiKey("sk-test").build();
        ModelInstance instance = factory.getOrCreate(endpoint);
        assertNotNull(instance.getChatClient());
        assertInstanceOf(ChatModel.class, instance.getChatModel());
        // cached
        assertSame(instance, factory.getOrCreate(endpoint));
    }

    @Test
    void factoryBuildsOpenAiEmbeddingModel() {
        ModelInstanceFactory factory = new ModelInstanceFactory(registry());
        ModelEndpoint endpoint = ModelEndpoint.builder()
                .id("e1").providerName("openai").modelName("text-embedding-3-small")
                .modelType(ModelType.TEXT_EMBEDDING).apiKey("sk-test").build();
        ModelInstance instance = factory.getOrCreate(endpoint);
        assertInstanceOf(EmbeddingModel.class, instance.getEmbeddingModel());
    }

    @Test
    void deepseekUsesItsDefaultBaseUrlWithoutError() {
        ModelInstanceFactory factory = new ModelInstanceFactory(registry());
        ModelEndpoint endpoint = ModelEndpoint.builder()
                .id("d1").providerName("deepseek").modelName("deepseek-chat")
                .modelType(ModelType.LLM).apiKey("sk-test").build();
        assertNotNull(factory.getOrCreate(endpoint).getChatModel());
    }

    @Test
    void missingApiKeyForOpenAiThrows() {
        ModelInstanceFactory factory = new ModelInstanceFactory(registry());
        ModelEndpoint endpoint = ModelEndpoint.builder()
                .id("x1").providerName("openai").modelName("gpt-4o")
                .modelType(ModelType.LLM).build();
        assertThrows(IllegalArgumentException.class, () -> factory.build(endpoint));
    }

    @Test
    void appBeansOverrideBuiltinsWithSameName() {
        io.github.aigoodle.model.provider.ModelProvider fakeZhipu =
                new io.github.aigoodle.model.provider.AbstractModelProvider() {
                    @Override public String getName() { return "zhipu"; }
                    @Override public java.util.Set<ModelType> supportedModelTypes() { return java.util.Set.of(ModelType.LLM); }
                    @Override public io.github.aigoodle.model.provider.CredentialSchema credentialSchema() {
                        return io.github.aigoodle.model.provider.CredentialSchema.of();
                    }
                };
        List<io.github.aigoodle.model.provider.ModelProvider> all = new ArrayList<>();
        all.add(fakeZhipu);                                        // app bean first
        all.addAll(BuiltinModelProviders.openAiCompatible());      // built-ins after
        ModelProviderRegistry registry = new ModelProviderRegistry(all);
        assertSame(fakeZhipu, registry.get("zhipu"),
                "app-published provider must beat the built-in with the same name");
    }
}
