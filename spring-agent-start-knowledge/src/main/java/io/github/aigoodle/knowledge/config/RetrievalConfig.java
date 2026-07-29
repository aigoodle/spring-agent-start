package io.github.aigoodle.knowledge.config;

import io.github.aigoodle.knowledge.enums.RetrievalMethod;
import lombok.Data;

/**
 * How queries are matched and ranked against a dataset.
 */
@Data
public class RetrievalConfig {

    private RetrievalMethod method = RetrievalMethod.HYBRID;

    private int topK = 5;

    /** Minimum fused score (0..1) for a chunk to be returned. */
    private double scoreThreshold = 0.0;

    /** Weight of the dense/vector score in HYBRID fusion (keyword weight = 1 - this). */
    private double vectorWeight = 0.7;

    /** Optional rerank model id (from the model module); null disables reranking. */
    private String rerankModelId;

    private boolean rerankEnabled = false;

    /**
     * Which reranker to use when {@link #rerankEnabled}. Matched against
     * {@code Reranker#getName()}. Values shipped by default: {@code noop},
     * {@code weighted}, {@code model}. Defaults to {@code weighted}.
     */
    private String rerankerName = "weighted";

    /** How many candidates to pass into the reranker (default: 4 &times; topK). */
    private int rerankPoolSize = 20;

    public double keywordWeight() {
        return Math.max(0.0, 1.0 - vectorWeight);
    }

    public static RetrievalConfig hybrid() {
        return new RetrievalConfig();
    }
}
