package io.github.aigoodle.rag.spi;

import io.github.aigoodle.rag.api.RagPolicy;
import io.github.aigoodle.rag.api.RetrievalCandidate;
import java.util.List;

public interface RagReranker {
    String getName();
    List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates, RagPolicy.Rerank policy);
}
