package io.github.aigoodle.memory;

import io.github.aigoodle.memory.config.MemoryProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded working memory plus persisted short/long-term memory with hybrid ranking. */
public class LayeredMemoryManager implements MemoryManager {
    private final MemoryStore store;
    private final MemoryProperties properties;
    private final Map<String, Deque<MemoryItem>> working = new ConcurrentHashMap<>();

    public LayeredMemoryManager(MemoryStore store, MemoryProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Override
    public MemoryItem remember(MemoryWrite write) {
        Objects.requireNonNull(write, "write");
        if (write.content() == null || write.content().isBlank()) {
            throw new IllegalArgumentException("Memory content must not be blank");
        }
        MemoryTier tier = write.tier() == null ? MemoryTier.SHORT_TERM : write.tier();
        if (tier == MemoryTier.SHORT_TERM && write.importance() >= properties.getLongTermThreshold()) {
            tier = MemoryTier.LONG_TERM;
        }
        Instant now = Instant.now();
        Instant expiresAt = tier == MemoryTier.SHORT_TERM
                ? now.plus(properties.getShortTermTtl()) : null;
        MemoryItem item = new MemoryItem(UUID.randomUUID().toString(), write.tenantId(),
                write.ownerId(), write.conversationId(), tier, write.role(), write.content(),
                write.importance(), now, expiresAt, 0, write.metadata());
        if (tier == MemoryTier.WORKING) {
            rememberWorking(item);
        } else {
            store.save(item);
        }
        return item;
    }

    @Override
    public List<MemoryItem> recall(MemoryQuery query) {
        Instant now = Instant.now();
        List<MemoryItem> candidates = new ArrayList<>();
        if (query.tiers().contains(MemoryTier.WORKING) && query.conversationId() != null) {
            candidates.addAll(working.getOrDefault(query.conversationId(), new ArrayDeque<>()));
        }
        Set<MemoryTier> persistedTiers = new HashSet<>(query.tiers());
        persistedTiers.remove(MemoryTier.WORKING);
        if (!persistedTiers.isEmpty()) {
            candidates.addAll(store.find(new MemoryQuery(query.tenantId(), query.ownerId(),
                    query.conversationId(), query.query(), persistedTiers, query.limit())));
        }
        return candidates.stream().filter(item -> !item.expired(now))
                .sorted(Comparator.comparingDouble((MemoryItem item) -> score(item, query.query(), now)).reversed()
                        .thenComparing(MemoryItem::createdAt, Comparator.reverseOrder()))
                .limit(query.limit()).toList();
    }

    @Override
    public void clearWorkingMemory(String conversationId) {
        if (conversationId != null) working.remove(conversationId);
    }

    private void rememberWorking(MemoryItem item) {
        if (item.conversationId() == null || item.conversationId().isBlank()) return;
        Deque<MemoryItem> window = working.computeIfAbsent(item.conversationId(), key -> new ArrayDeque<>());
        synchronized (window) {
            window.addLast(item);
            while (window.size() > Math.max(1, properties.getWorkingCapacity())) window.removeFirst();
        }
    }

    private double score(MemoryItem item, String query, Instant now) {
        double ageHours = Math.max(0, Duration.between(item.createdAt(), now).toMinutes() / 60.0);
        double recency = Math.exp(-ageHours / (item.tier() == MemoryTier.LONG_TERM ? 720.0 : 72.0));
        return properties.getRecencyWeight() * recency
                + properties.getRelevanceWeight() * lexicalRelevance(item.content(), query)
                + properties.getImportanceWeight() * item.importance();
    }

    static double lexicalRelevance(String content, String query) {
        if (query == null || query.isBlank()) return 0;
        Set<String> terms = new HashSet<>(Arrays.asList(query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")));
        terms.remove("");
        if (terms.isEmpty()) return 0;
        String normalized = content.toLowerCase(Locale.ROOT);
        long matches = terms.stream().filter(normalized::contains).count();
        return (double) matches / terms.size();
    }
}
