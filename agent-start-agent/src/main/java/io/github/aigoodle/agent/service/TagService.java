package io.github.aigoodle.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.agent.entity.TagBindingEntity;
import io.github.aigoodle.agent.entity.TagEntity;
import io.github.aigoodle.agent.mapper.TagBindingMapper;
import io.github.aigoodle.agent.mapper.TagMapper;
import io.github.aigoodle.common.exception.AgentException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * CRUD for {@link TagEntity} + binding management. The tags are tenant-scoped
 * and typed ({@code app} vs {@code knowledge}) so app filters and dataset
 * filters share the same primitive without stepping on each other.
 */
public class TagService {

    private final TagMapper tagMapper;
    private final TagBindingMapper bindingMapper;

    public TagService(TagMapper tagMapper, TagBindingMapper bindingMapper) {
        this.tagMapper = tagMapper;
        this.bindingMapper = bindingMapper;
    }

    public List<TagEntity> list(String tenantId, String type) {
        return tagMapper.selectList(new LambdaQueryWrapper<TagEntity>()
                .eq(TagEntity::getTenantId, tenantId == null ? "default" : tenantId)
                .eq(type != null, TagEntity::getType, type)
                .orderByAsc(TagEntity::getName));
    }

    public TagEntity require(String id) {
        TagEntity e = tagMapper.selectById(id);
        if (e == null) {
            throw new AgentException("tag_not_found", "Tag not found: " + id, null);
        }
        return e;
    }

    @Transactional
    public TagEntity create(TagEntity entity) {
        if (entity.getType() == null) entity.setType("app");
        if (entity.getTenantId() == null) entity.setTenantId("default");
        tagMapper.insert(entity);
        return entity;
    }

    @Transactional
    public TagEntity rename(String id, String name) {
        TagEntity e = require(id);
        e.setName(name);
        tagMapper.updateById(e);
        return e;
    }

    @Transactional
    public void delete(String id) {
        tagMapper.deleteById(id);
        bindingMapper.delete(new LambdaQueryWrapper<TagBindingEntity>()
                .eq(TagBindingEntity::getTagId, id));
    }

    // ---------------------------------------------------------------- bindings

    public List<TagBindingEntity> bindings(String targetId, String targetType) {
        return bindingMapper.selectList(new LambdaQueryWrapper<TagBindingEntity>()
                .eq(TagBindingEntity::getTargetId, targetId)
                .eq(targetType != null, TagBindingEntity::getTargetType, targetType));
    }

    @Transactional
    public void bind(String tagId, String targetId, String targetType) {
        TagBindingEntity existing = bindingMapper.selectOne(new LambdaQueryWrapper<TagBindingEntity>()
                .eq(TagBindingEntity::getTagId, tagId)
                .eq(TagBindingEntity::getTargetId, targetId)
                .last("LIMIT 1"));
        if (existing != null) return;
        TagBindingEntity fresh = new TagBindingEntity();
        fresh.setTagId(tagId);
        fresh.setTargetId(targetId);
        fresh.setTargetType(targetType == null ? "app" : targetType);
        bindingMapper.insert(fresh);
    }

    @Transactional
    public void unbind(String tagId, String targetId) {
        bindingMapper.delete(new LambdaQueryWrapper<TagBindingEntity>()
                .eq(TagBindingEntity::getTagId, tagId)
                .eq(TagBindingEntity::getTargetId, targetId));
    }
}
