package io.github.aigoodle.rag.spi;

import io.github.aigoodle.rag.api.RagPolicy;
import io.github.aigoodle.rag.api.RetrievalCandidate;
import java.util.List;

public interface FusionStrategy {
    RagPolicy.FusionMethod getMethod();
    List<RetrievalCandidate> fuse(List<List<RetrievalCandidate>> resultSets, RagPolicy.Fusion policy);
}
