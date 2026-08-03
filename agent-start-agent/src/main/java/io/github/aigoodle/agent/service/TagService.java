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

    private static final String DEFAULT_TENANT_ID = "default";
    private static final String DEFAULT_TARGET_TYPE = "app";

    private final TagMapper tagMapper;
    private final TagBindingMapper bindingMapper;

    public TagService(TagMapper tagMapper, TagBindingMapper bindingMapper) {
        this.tagMapper = tagMapper;
        this.bindingMapper = bindingMapper;
    }

    public List<TagEntity> list(String tenantId, String type) {
        return tagMapper.selectList(new LambdaQueryWrapper<TagEntity>()
                .eq(TagEntity::getTenantId, valueOrDefault(tenantId, DEFAULT_TENANT_ID))
                .eq(type != null, TagEntity::getType, type)
                .orderByAsc(TagEntity::getName));
    }

    public TagEntity require(String tagId) {
        TagEntity tag = tagMapper.selectById(tagId);
        if (tag == null) {
            throw new AgentException("tag_not_found", "Tag not found: " + tagId, null);
        }
        return tag;
    }

    @Transactional
    public TagEntity create(TagEntity tag) {
        tag.setType(valueOrDefault(tag.getType(), DEFAULT_TARGET_TYPE));
        tag.setTenantId(valueOrDefault(tag.getTenantId(), DEFAULT_TENANT_ID));
        tagMapper.insert(tag);
        return tag;
    }

    @Transactional
    public TagEntity rename(String tagId, String name) {
        TagEntity tag = require(tagId);
        tag.setName(name);
        tagMapper.updateById(tag);
        return tag;
    }

    @Transactional
    public void delete(String tagId) {
        bindingMapper.delete(new LambdaQueryWrapper<TagBindingEntity>()
                .eq(TagBindingEntity::getTagId, tagId));
        tagMapper.deleteById(tagId);
    }

    // ---------------------------------------------------------------- bindings

    public List<TagBindingEntity> bindings(String targetId, String targetType) {
        return bindingMapper.selectList(new LambdaQueryWrapper<TagBindingEntity>()
                .eq(TagBindingEntity::getTargetId, targetId)
                .eq(targetType != null, TagBindingEntity::getTargetType, targetType));
    }

    @Transactional
    public void bind(String tagId, String targetId, String targetType) {
        TagEntity tag = require(tagId);
        String resolvedTargetType = valueOrDefault(targetType, DEFAULT_TARGET_TYPE);
        TagBindingEntity existingBinding = bindingMapper.selectOne(
                new LambdaQueryWrapper<TagBindingEntity>()
                .eq(TagBindingEntity::getTagId, tagId)
                .eq(TagBindingEntity::getTargetId, targetId)
                .eq(TagBindingEntity::getTargetType, resolvedTargetType)
                .last("LIMIT 1"));
        if (existingBinding != null) {
            return;
        }

        TagBindingEntity newBinding = new TagBindingEntity();
        newBinding.setTenantId(valueOrDefault(tag.getTenantId(), DEFAULT_TENANT_ID));
        newBinding.setTagId(tagId);
        newBinding.setTargetId(targetId);
        newBinding.setTargetType(resolvedTargetType);
        bindingMapper.insert(newBinding);
    }

    @Transactional
    public void unbind(String tagId, String targetId) {
        bindingMapper.delete(new LambdaQueryWrapper<TagBindingEntity>()
                .eq(TagBindingEntity::getTagId, tagId)
                .eq(TagBindingEntity::getTargetId, targetId));
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
