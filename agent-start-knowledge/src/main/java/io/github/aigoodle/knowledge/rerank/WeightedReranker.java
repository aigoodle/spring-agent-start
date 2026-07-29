package io.github.aigoodle.knowledge.rerank;

import io.github.aigoodle.knowledge.retrieve.RetrievedSegment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Rescores candidates by blending vector score, keyword score and content-length prior.
 * The prior mildly penalises very short / very long chunks, which is the same heuristic
 * Dify's "weighted score" reranker applies.
 * <p>
 * Weights are configured via {@code spring-agent.knowledge.reranker.weighted.*}:
 * {@code vectorWeight} + {@code keywordWeight} + {@code lengthWeight} should sum to 1
 * (they are renormalised if they don't).
 */
public class WeightedReranker implements Reranker {

    public static final String NAME = "weighted";

    private final double vectorWeight;
    private final double keywordWeight;
    private final double lengthWeight;
    private final int idealLength;

    public WeightedReranker() {
        this(0.6, 0.3, 0.1, 400);
    }

    public WeightedReranker(double vectorWeight, double keywordWeight, double lengthWeight, int idealLength) {
        double sum = vectorWeight + keywordWeight + lengthWeight;
        if (sum <= 0) {
            sum = 1;
        }
        this.vectorWeight = vectorWeight / sum;
        this.keywordWeight = keywordWeight / sum;
        this.lengthWeight = lengthWeight / sum;
        this.idealLength = Math.max(50, idealLength);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public List<RetrievedSegment> rerank(String query, List<RetrievedSegment> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<RetrievedSegment> rescored = new ArrayList<>(candidates.size());
        for (RetrievedSegment s : candidates) {
            double lengthScore = lengthPrior(s.getContent());
            double fused = vectorWeight * s.getVectorScore()
                    + keywordWeight * s.getKeywordScore()
                    + lengthWeight * lengthScore;
            s.setScore(fused);
            rescored.add(s);
        }
        rescored.sort(Comparator.comparingDouble(RetrievedSegment::getScore).reversed());
        if (topN > 0 && rescored.size() > topN) {
            return rescored.subList(0, topN);
        }
        return rescored;
    }

    /** Bell curve peaking at {@link #idealLength}. Very short or very long chunks lose score. */
    private double lengthPrior(String content) {
        int len = content == null ? 0 : content.length();
        if (len == 0) {
            return 0.0;
        }
        double ratio = (double) len / idealLength;
        // Gaussian-like drop-off centred at 1.0.
        double x = Math.log(ratio);
        return Math.exp(-0.5 * x * x);
    }
}
