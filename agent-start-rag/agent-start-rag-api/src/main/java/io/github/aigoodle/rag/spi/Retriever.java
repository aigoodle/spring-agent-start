package io.github.aigoodle.rag.spi;

import io.github.aigoodle.rag.api.PlannedQuery;
import io.github.aigoodle.rag.api.RagPolicy;
import io.github.aigoodle.rag.api.RagRequest;
import io.github.aigoodle.rag.api.RetrievalCandidate;
import java.util.List;

public interface Retriever {
    String getName();
    RagPolicy.Channel getChannel();
    default boolean available(RagRequest request) { return true; }
    List<RetrievalCandidate> retrieve(PlannedQuery query, RagRequest request);
}
