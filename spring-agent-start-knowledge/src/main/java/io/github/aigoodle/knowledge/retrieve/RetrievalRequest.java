package io.github.aigoodle.knowledge.retrieve;

import io.github.aigoodle.knowledge.enums.RetrievalMethod;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * A retrieval query. Null overrides fall back to the dataset's configured defaults.
 */
@Data
@Builder
public class RetrievalRequest {

    private String query;

    private Integer topK;

    private RetrievalMethod method;

    private Double scoreThreshold;

    /** Override the dense/vector fusion weight (0..1). */
    private Double vectorWeight;

    /** Equality filter applied to chunk metadata (e.g. {@code {"category":"faq"}}). */
    private Map<String, Object> metadataFilter;
}
