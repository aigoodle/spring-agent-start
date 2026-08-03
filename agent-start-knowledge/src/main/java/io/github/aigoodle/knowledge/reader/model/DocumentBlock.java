package io.github.aigoodle.knowledge.reader.model;

import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

import java.util.HashMap;
import java.util.Map;

/** One ordered, attributable unit in a parsed document. */
@Data
@Builder
@Jacksonized
public class DocumentBlock {
    private int index;
    private BlockType type;
    private String text;
    private Integer page;
    private Integer headingLevel;
    private String headingPath;
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
}
