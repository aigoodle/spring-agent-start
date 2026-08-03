package io.github.aigoodle.knowledge.retrieve;

import io.github.aigoodle.knowledge.config.RetrievalConfig;
import io.github.aigoodle.knowledge.enums.RetrievalMethod;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalPlanTest {

    @Test
    void requestValuesOverrideDatasetDefaults() {
        RetrievalConfig config = RetrievalConfig.hybrid();
        config.setTopK(5);
        config.setScoreThreshold(0.2);
        RetrievalRequest request = RetrievalRequest.builder()
                .method(RetrievalMethod.HYBRID)
                .topK(2)
                .scoreThreshold(0.6)
                .vectorWeight(0.25)
                .build();

        RetrievalPlan plan = RetrievalPlan.resolve(config, request, true);

        assertThat(plan.topK()).isEqualTo(2);
        assertThat(plan.scoreThreshold()).isEqualTo(0.6);
        assertThat(plan.fusedScore(0.8, 0.4)).isEqualTo(0.5);
    }

    @Test
    void vectorOnlyRetrievalFallsBackToKeywordsWithoutVectorIndex() {
        RetrievalConfig config = RetrievalConfig.hybrid();
        RetrievalRequest request = RetrievalRequest.builder()
                .method(RetrievalMethod.VECTOR)
                .build();

        RetrievalPlan plan = RetrievalPlan.resolve(config, request, false);

        assertThat(plan.method()).isEqualTo(RetrievalMethod.FULL_TEXT);
        assertThat(plan.usesKeywords()).isTrue();
        assertThat(plan.usesVectors(false)).isFalse();
    }

    @Test
    void reciprocalRankFusionRewardsAgreementAcrossRecallChannels() {
        RetrievalConfig config = RetrievalConfig.hybrid();
        config.setVectorWeight(0.5);
        config.setFusionMethod(RetrievalConfig.FusionMethod.RECIPROCAL_RANK);
        RetrievalPlan plan = RetrievalPlan.resolve(config, RetrievalRequest.builder().query("rag").build(), true);

        double foundByBoth = plan.fusedScore(0.2, 0.2, 2, 2);
        double vectorOnly = plan.fusedScore(0.99, 0.0, 1, 0);

        assertThat(foundByBoth).isGreaterThan(vectorOnly);
    }
}
