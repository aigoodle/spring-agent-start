package io.github.aigoodle.rag.spi;

import io.github.aigoodle.rag.api.Evidence;
import io.github.aigoodle.rag.api.RagPolicy;
import io.github.aigoodle.rag.api.RagRequest;
import java.util.List;

public interface ContextProcessor {
    int getOrder();
    List<Evidence> process(RagRequest request, List<Evidence> evidence, RagPolicy.Context policy);
}
