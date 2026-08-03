package io.github.aigoodle.knowledge.rerank;

import io.github.aigoodle.knowledge.retrieve.RetrievedSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WeightedRerankerTest {

    @Test
    void combinesSignalsAndReturnsOnlyTheHighestScoringCandidates() {
        WeightedReranker reranker = new WeightedReranker(
                new WeightedRerankerSettings(0.5, 0.5, 0, 400));
        RetrievedSegment vectorMatch = candidate("vector", 1.0, 0.0);
        RetrievedSegment balancedMatch = candidate("balanced", 0.8, 0.8);
        RetrievedSegment keywordMatch = candidate("keyword", 0.0, 1.0);

        List<RetrievedSegment> results = reranker.rerank(
                "query", List.of(vectorMatch, balancedMatch, keywordMatch), 2);

        assertThat(results).extracting(RetrievedSegment::getContent)
                .containsExactly("balanced", "vector");
    }

    private static RetrievedSegment candidate(
            String content, double vectorScore, double keywordScore) {
        return RetrievedSegment.builder()
                .content(content)
                .vectorScore(vectorScore)
                .keywordScore(keywordScore)
                .build();
    }
}
