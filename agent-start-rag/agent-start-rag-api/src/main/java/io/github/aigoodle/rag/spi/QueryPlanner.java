package io.github.aigoodle.rag.spi;

import io.github.aigoodle.rag.api.QueryPlan;
import io.github.aigoodle.rag.api.RagRequest;

public interface QueryPlanner {
    String getName();
    QueryPlan plan(RagRequest request);
}
