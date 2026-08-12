package io.github.aigoodle.memory.extraction;

import io.github.aigoodle.memory.MemoryWrite;

import java.util.List;

/** SPI for extracting durable facts/preferences; implementations may use rules or an LLM. */
@FunctionalInterface
public interface MemoryExtractor {
    List<MemoryWrite> extract(MemoryExchange exchange);
}
