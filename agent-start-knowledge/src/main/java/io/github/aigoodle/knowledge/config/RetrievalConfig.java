package io.github.aigoodle.knowledge.config;

import io.github.aigoodle.knowledge.enums.RetrievalMethod;
import lombok.Data;

/**
 * How queries are matched and ranked against a dataset.
 */
@Data
public class RetrievalConfig {

    public enum FusionMethod {
        /** Blend scores directly. Best when both stores expose calibrated scores. */
        WEIGHTED_SCORE,
        /** Reciprocal-rank fusion is robust when dense and sparse score scales differ. */
        RECIPROCAL_RANK
    }

    private RetrievalMethod method = RetrievalMethod.HYBRID;

    private int topK = 5;

    /** Minimum fused score (0..1) for a chunk to be returned. */
    private double scoreThreshold = 0.0;

    /** Weight of the dense/vector score in HYBRID fusion (keyword weight = 1 - this). */
    private double vectorWeight = 0.7;

    private FusionMethod fusionMethod = FusionMethod.RECIPROCAL_RANK;

    /** RRF smoothing constant; lower values put more emphasis on the first few hits. */
    private int rrfK = 60;

    /** Candidate multiplier before fusion/reranking. */
    private int recallMultiplier = 6;

    /** Diversity guard; zero means unlimited chunks from the same document. */
    private int maxChunksPerDocument = 0;

    /** Expand each hit with this many preceding/following chunks from the same document. */
    private int neighborWindow = 0;

    /** Normalize the query and generate conservative lexical variants before recall. */
    private boolean queryExpansionEnabled = true;

    /** Maximum number of query variants, including the original query. */
    private int maxQueryVariants = 3;

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
