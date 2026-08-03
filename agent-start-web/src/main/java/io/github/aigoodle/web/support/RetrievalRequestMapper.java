package io.github.aigoodle.web.support;

import io.github.aigoodle.knowledge.retrieve.RetrievalRequest;
import io.github.aigoodle.web.dto.RetrieveRequestDto;

/** Maps the web retrieval payload onto the knowledge module's domain request. */
public final class RetrievalRequestMapper {

    private RetrievalRequestMapper() {
    }

    public static RetrievalRequest from(RetrieveRequestDto request) {
        return RetrievalRequest.builder()
                .query(request.getQuery())
                .method(request.getMethod())
                .topK(request.getTopK())
                .scoreThreshold(request.getScoreThreshold())
                .vectorWeight(request.getVectorWeight())
                .metadataFilter(request.getMetadataFilter())
                .build();
    }
}
