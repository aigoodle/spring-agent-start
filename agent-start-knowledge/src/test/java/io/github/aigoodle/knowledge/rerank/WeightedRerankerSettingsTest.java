package io.github.aigoodle.knowledge.rerank;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class WeightedRerankerSettingsTest {

    @Test
    void normalizesWeightsAndAppliesTheMinimumIdealLength() {
        WeightedRerankerSettings settings = new WeightedRerankerSettings(6, 3, 1, 10);

        assertThat(settings.vectorWeight()).isEqualTo(0.6);
        assertThat(settings.keywordWeight()).isEqualTo(0.3);
        assertThat(settings.lengthWeight()).isEqualTo(0.1);
        assertThat(settings.idealContentLength()).isEqualTo(50);
    }

    @Test
    void rejectsWeightsThatCannotRepresentAValidContribution() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WeightedRerankerSettings(-1, 1, 1, 400))
                .withMessageContaining("vectorWeight");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WeightedRerankerSettings(Double.NaN, 1, 1, 400))
                .withMessageContaining("vectorWeight");
    }
}
