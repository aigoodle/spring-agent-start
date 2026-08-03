package io.github.aigoodle.model.service;

import io.github.aigoodle.model.ModelTestApplication;
import io.github.aigoodle.model.entity.ModelEntity;
import io.github.aigoodle.model.entity.ProviderCredentialEntity;
import io.github.aigoodle.model.enums.ModelType;
import io.github.aigoodle.model.provider.ModelEndpoint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full persistence round-trip on H2: register a model, read it back, resolve its
 * (decrypted, merged) endpoint and obtain a cached runtime instance.
 */
@SpringBootTest(classes = ModelTestApplication.class)
class ModelServiceIntegrationTest {

    @Autowired
    private ModelService modelService;

    @Autowired
    private ProviderCredentialService credentialService;

    @Test
    void registerResolveAndInstantiate() {
        ModelEntity entity = modelService.register(ModelRegistration.builder()
                .tenantId("acme")
                .providerName("openai")
                .modelName("gpt-4o-mini")
                .modelType(ModelType.LLM)
                .credentials(Map.of("apiKey", "sk-integration", "baseUrl", "https://api.example.com"))
                .asDefault(true)
                .build());

        assertNotNull(entity.getId());

        // persisted + readable
        ModelEntity loaded = modelService.require(entity.getId());
        assertEquals("openai", loaded.getProviderName());
        assertEquals(ModelType.LLM, loaded.getModelType());
        assertNotEquals("sk-integration", loaded.getEncryptedConfig(), "secret must be encrypted at rest");

        // endpoint decrypts + splits apiKey/baseUrl
        ModelEndpoint endpoint = modelService.resolveEndpoint(entity.getId());
        assertEquals("sk-integration", endpoint.getApiKey());
        assertEquals("https://api.example.com", endpoint.getBaseUrl());

        // runtime instance
        assertNotNull(modelService.getChatClient(entity.getId()));

        // default lookup
        ModelEntity def = modelService.getDefault("acme", ModelType.LLM);
        assertEquals(entity.getId(), def.getId());

        // listing by type
        List<ModelEntity> llms = modelService.listByType("acme", ModelType.LLM);
        assertEquals(1, llms.size());
    }

    @Test
    void updateCredentialsMergesPatchIntoExisting() {
        ModelEntity entity = modelService.register(ModelRegistration.builder()
                .tenantId("rotate").providerName("openai").modelName("gpt-4o")
                .modelType(ModelType.LLM)
                .credentials(Map.of("apiKey", "sk-old", "baseUrl", "https://old.example.com"))
                .build());

        // Rotate ONLY the apiKey — baseUrl should survive.
        modelService.updateCredentials(entity.getId(), Map.of("apiKey", "sk-new"));

        ModelEndpoint endpoint = modelService.resolveEndpoint(entity.getId());
        assertEquals("sk-new", endpoint.getApiKey(), "apiKey should be replaced");
        assertEquals("https://old.example.com", endpoint.getBaseUrl(),
                "baseUrl should be preserved by the merge semantics");

        // Empty patch is a no-op.
        modelService.updateCredentials(entity.getId(), Map.of());
        endpoint = modelService.resolveEndpoint(entity.getId());
        assertEquals("sk-new", endpoint.getApiKey());
        assertEquals("https://old.example.com", endpoint.getBaseUrl());
    }

    @Test
    void onlyOneDefaultPerType() {
        modelService.register(ModelRegistration.builder()
                .tenantId("dft").providerName("openai").modelName("gpt-4o")
                .modelType(ModelType.LLM).credentials(Map.of("apiKey", "k1")).asDefault(true).build());
        ModelEntity second = modelService.register(ModelRegistration.builder()
                .tenantId("dft").providerName("deepseek").modelName("deepseek-chat")
                .modelType(ModelType.LLM).credentials(Map.of("apiKey", "k2")).asDefault(true).build());

        ModelEntity def = modelService.getDefault("dft", ModelType.LLM);
        assertEquals(second.getId(), def.getId(), "registering a new default unsets the previous one");
    }

    @Test
    void sharedProviderCredentialIsInherited() {
        ProviderCredentialEntity credential = credentialService.save(
                new ProviderCredentialRegistration(
                        "shared", "openai", "team-key",
                        Map.of("apiKey", "sk-shared", "baseUrl", "https://shared.example.com")));

        ModelEntity model = modelService.register(ModelRegistration.builder()
                .tenantId("shared").providerName("openai").modelName("gpt-4o")
                .modelType(ModelType.LLM).credentialId(credential.getId()).build());

        ModelEndpoint endpoint = modelService.resolveEndpoint(model.getId());
        assertEquals("sk-shared", endpoint.getApiKey());
        assertEquals("https://shared.example.com", endpoint.getBaseUrl());
    }

    @Test
    void validateRejectsMissingApiKey() {
        assertThrows(RuntimeException.class, () -> modelService.validate(ModelRegistration.builder()
                .providerName("openai").modelName("gpt-4o").modelType(ModelType.LLM)
                .credentials(Map.of()).build()));
    }
}
