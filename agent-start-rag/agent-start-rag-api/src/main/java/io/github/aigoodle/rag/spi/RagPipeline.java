package io.github.aigoodle.rag.spi;

import io.github.aigoodle.rag.api.RagRequest;
import io.github.aigoodle.rag.api.RagResult;

public interface RagPipeline { RagResult retrieve(RagRequest request); }
