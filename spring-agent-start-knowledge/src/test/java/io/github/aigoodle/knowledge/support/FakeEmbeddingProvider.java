package io.github.aigoodle.knowledge.support;

import io.github.aigoodle.model.enums.ModelType;
import io.github.aigoodle.model.provider.AbstractModelProvider;
import io.github.aigoodle.model.provider.CredentialSchema;
import io.github.aigoodle.model.provider.ModelEndpoint;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.Set;

/**
 * Test model provider that yields a {@link HashingEmbeddingModel}, registered as a
 * Spring bean so the model registry discovers it like any third-party provider.
 */
public class FakeEmbeddingProvider extends AbstractModelProvider {

    @Override
    public String getName() {
        return "fake";
    }

    @Override
    public Set<ModelType> supportedModelTypes() {
        return Set.of(ModelType.TEXT_EMBEDDING);
    }

    @Override
    public CredentialSchema credentialSchema() {
        return CredentialSchema.of();
    }

    @Override
    public EmbeddingModel createEmbeddingModel(ModelEndpoint endpoint) {
        Integer dim = endpoint.intProperty("dimensions");
        return new HashingEmbeddingModel(dim == null ? 256 : dim);
    }
}
