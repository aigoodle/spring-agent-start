package io.github.aigoodle.knowledge.retrieve;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * A scored chunk returned from retrieval, with source attribution and the individual
 * vector/keyword scores that produced the fused {@link #score}.
 */
@Data
@Builder
public class RetrievedSegment {

    private String segmentId;
    private String datasetId;
    private String documentId;
    private Integer position;

    private String content;

    /** Larger parent context for PARENT_CHILD chunks; null otherwise. */
    private String parentContent;

    private double score;
    private double vectorScore;
    private double keywordScore;

    private Map<String, Object> metadata;

    /** Best text to feed an LLM: the parent context when available, else the chunk. */
    public String contextText() {
        return parentContent != null && !parentContent.isBlank() ? parentContent : content;
    }
}
