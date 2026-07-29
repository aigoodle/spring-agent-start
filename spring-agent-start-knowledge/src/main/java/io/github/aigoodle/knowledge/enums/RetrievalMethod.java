package io.github.aigoodle.knowledge.enums;

/**
 * How a query is matched against indexed chunks.
 */
public enum RetrievalMethod {

    /** Dense embedding similarity only. */
    VECTOR,

    /** Sparse keyword / term-overlap matching only. */
    FULL_TEXT,

    /** Fuse vector + keyword scores (weighted). The recommended default. */
    HYBRID
}
