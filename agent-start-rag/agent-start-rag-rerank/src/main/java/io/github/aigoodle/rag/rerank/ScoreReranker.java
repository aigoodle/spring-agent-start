package io.github.aigoodle.rag.rerank;

import io.github.aigoodle.rag.api.RagPolicy;
import io.github.aigoodle.rag.api.RetrievalCandidate;
import io.github.aigoodle.rag.spi.RagReranker;
import java.util.Comparator;
import java.util.List;

/** Deterministic fallback and extension point for external cross-encoder providers. */
public final class ScoreReranker implements RagReranker {
    @Override public String getName() { return "score"; }
    @Override public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates,
                                                     RagPolicy.Rerank policy) {
        return candidates.stream().sorted(Comparator.comparingDouble(RetrievalCandidate::score).reversed())
                .limit(Math.max(1, policy.topN())).toList();
    }
}
