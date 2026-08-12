package io.github.aigoodle.memory;

import java.time.Instant;
import java.util.List;
import java.util.Collection;

/** Persistence SPI; applications may replace JDBC with a vector or remote store. */
public interface MemoryStore {
    void save(MemoryItem item);
    List<MemoryItem> find(MemoryQuery query);
    default void delete(String tenantId, String ownerId, String conversationId) { }
    default void purgeExpired(Instant now) { }
    /** Records successful recall so stores can reinforce frequently useful memories. */
    default void recordAccess(Collection<String> memoryIds, Instant accessedAt) { }
}
