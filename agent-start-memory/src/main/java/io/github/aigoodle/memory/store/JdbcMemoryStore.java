package io.github.aigoodle.memory.store;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.memory.*;
import io.github.aigoodle.memory.entity.MemoryEntity;
import io.github.aigoodle.memory.mapper.MemoryMapper;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

public class JdbcMemoryStore implements MemoryStore {
    private final MemoryMapper mapper;

    public JdbcMemoryStore(MemoryMapper mapper) { this.mapper = mapper; }

    @Override
    @Transactional
    public void save(MemoryItem item) {
        MemoryEntity entity = new MemoryEntity();
        entity.setId(item.id());
        entity.setTenantId(item.tenantId());
        entity.setOwnerId(item.ownerId());
        entity.setConversationId(item.conversationId());
        entity.setTier(item.tier().name());
        entity.setRole(item.role().name());
        entity.setContent(item.content());
        entity.setImportance(item.importance());
        entity.setExpiresAt(toLocal(item.expiresAt()));
        entity.setAccessCount(item.accessCount());
        mapper.insert(entity);
    }

    @Override
    public List<MemoryItem> find(MemoryQuery query) {
        LambdaQueryWrapper<MemoryEntity> wrapper = new LambdaQueryWrapper<MemoryEntity>()
                .eq(MemoryEntity::getTenantId, query.tenantId())
                .in(MemoryEntity::getTier, query.tiers().stream().map(Enum::name).toList())
                .and(query.ownerId() != null && !query.ownerId().isBlank(),
                        part -> part.eq(MemoryEntity::getOwnerId, query.ownerId())
                                .or().isNull(MemoryEntity::getOwnerId))
                .eq(query.conversationId() != null && !query.conversationId().isBlank(),
                        MemoryEntity::getConversationId, query.conversationId())
                .and(part -> part.isNull(MemoryEntity::getExpiresAt)
                        .or().gt(MemoryEntity::getExpiresAt, LocalDateTime.now()))
                .orderByDesc(MemoryEntity::getCreatedAt)
                .last("limit " + Math.min(1000, query.limit() * 8));
        return mapper.selectList(wrapper).stream().map(JdbcMemoryStore::toItem).toList();
    }

    @Override
    public void purgeExpired(Instant now) {
        mapper.delete(new LambdaQueryWrapper<MemoryEntity>()
                .lt(MemoryEntity::getExpiresAt, toLocal(now)));
    }

    private static MemoryItem toItem(MemoryEntity value) {
        return new MemoryItem(value.getId(), value.getTenantId(), value.getOwnerId(),
                value.getConversationId(), MemoryTier.valueOf(value.getTier()),
                MemoryRole.valueOf(value.getRole()), value.getContent(),
                value.getImportance() == null ? 0.5 : value.getImportance(),
                toInstant(value.getCreatedAt()), toInstant(value.getExpiresAt()),
                value.getAccessCount() == null ? 0 : value.getAccessCount(), Map.of());
    }

    private static LocalDateTime toLocal(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
