package io.github.aigoodle.knowledge.rerank;

import io.github.aigoodle.knowledge.retrieve.RetrievedSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRerankPromptTest {

    @Test
    void labelsAndAbbreviatesCandidatePassages() {
        RetrievedSegment longPassage = RetrievedSegment.builder()
                .content("x".repeat(650))
                .build();
        RetrievedSegment missingPassage = RetrievedSegment.builder().content(null).build();

        String prompt = ModelRerankPrompt.render(
                "Which passage is relevant?", List.of(longPassage, missingPassage));

        assertThat(prompt).contains("Query: Which passage is relevant?");
        assertThat(prompt).contains("[0] " + "x".repeat(600) + "…");
        assertThat(prompt).contains("[1] \n\n");
        assertThat(prompt).doesNotContain("x".repeat(601));
    }
}
