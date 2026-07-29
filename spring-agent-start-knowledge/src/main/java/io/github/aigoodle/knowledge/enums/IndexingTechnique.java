package io.github.aigoodle.knowledge.enums;

/**
 * Quality vs cost trade-off for indexing.
 */
public enum IndexingTechnique {

    /** Embedding-based dense index — best recall. */
    HIGH_QUALITY,

    /** Keyword-only index — no embedding cost. */
    ECONOMY
}
