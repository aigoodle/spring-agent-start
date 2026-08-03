package io.github.aigoodle.memory;

import java.util.List;

/** High-level lifecycle and retrieval facade used by agent runtimes. */
public interface MemoryManager {
    MemoryItem remember(MemoryWrite write);
    List<MemoryItem> recall(MemoryQuery query);
    void clearWorkingMemory(String conversationId);
}
