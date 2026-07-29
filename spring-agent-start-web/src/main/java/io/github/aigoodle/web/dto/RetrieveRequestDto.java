package io.github.aigoodle.web.dto;

import io.github.aigoodle.knowledge.enums.RetrievalMethod;
import lombok.Data;

import java.util.Map;

/**
 * Frontend-facing retrieval request. Mirrors {@code RetrievalRequest} but decouples
 * the wire format from the internal builder so we can add fields without breaking
 * clients.
 */
@Data
public class RetrieveRequestDto {

    private String query;
    private RetrievalMethod method;
    private Integer topK;
    private Double scoreThreshold;
    private Double vectorWeight;
    private Map<String, Object> metadataFilter;
}
