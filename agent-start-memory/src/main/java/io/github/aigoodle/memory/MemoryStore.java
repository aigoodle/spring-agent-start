package io.github.aigoodle.memory;

import java.time.Instant;
import java.util.List;

/** Persistence SPI; applications may replace JDBC with a vector or remote store. */
public interface MemoryStore {
    void save(MemoryItem item);
    List<MemoryItem> find(MemoryQuery query);
    default void purgeExpired(Instant now) { }
}
