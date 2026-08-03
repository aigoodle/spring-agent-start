package io.github.aigoodle.memory;

import io.github.aigoodle.memory.config.MemoryProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LayeredMemoryManagerTest {
    private final RecordingStore store = new RecordingStore();
    private final MemoryProperties properties = new MemoryProperties();
    private final LayeredMemoryManager manager = new LayeredMemoryManager(store, properties);

    @Test
    void keepsWorkingMemoryBounded() {
        properties.setWorkingCapacity(2);
        for (int index = 0; index < 3; index++) {
            manager.remember(new MemoryWrite("default", "a", "c", MemoryTier.WORKING,
                    MemoryRole.USER, "message " + index, .5, null));
        }
        List<MemoryItem> result = manager.recall(new MemoryQuery("default", "a", "c", null,
                Set.of(MemoryTier.WORKING), 10));
        assertThat(result).extracting(MemoryItem::content).containsExactly("message 1", "message 2");
    }

    @Test
    void promotesImportantShortTermMemory() {
        MemoryItem item = manager.remember(new MemoryWrite("default", "a", "c",
                MemoryTier.SHORT_TERM, MemoryRole.FACT, "User prefers concise answers", .9, null));
        assertThat(item.tier()).isEqualTo(MemoryTier.LONG_TERM);
        assertThat(item.expiresAt()).isNull();
    }

    @Test
    void semanticWordsOutrankUnrelatedRecentItems() {
        store.items.add(new MemoryItem("1", "default", "a", "c", MemoryTier.LONG_TERM,
                MemoryRole.FACT, "production database is PostgreSQL", .8,
                Instant.now().minusSeconds(3600), null, 0, null));
        store.items.add(new MemoryItem("2", "default", "a", "c", MemoryTier.LONG_TERM,
                MemoryRole.FACT, "likes blue", .8, Instant.now(), null, 0, null));
        assertThat(manager.recall(new MemoryQuery("default", "a", "c", "PostgreSQL database",
                Set.of(MemoryTier.LONG_TERM), 1))).extracting(MemoryItem::id).containsExactly("1");
    }

    private static class RecordingStore implements MemoryStore {
        private final List<MemoryItem> items = new ArrayList<>();
        public void save(MemoryItem item) { items.add(item); }
        public List<MemoryItem> find(MemoryQuery query) { return List.copyOf(items); }
    }
}
