package io.github.aigoodle.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.agent.entity.AppAnnotationEntity;
import io.github.aigoodle.agent.mapper.AppAnnotationMapper;
import io.github.aigoodle.common.exception.AgentException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * CRUD service for {@link AppAnnotationEntity} — the per-app QA pairs surfaced
 * in the drawer's 日志与标注 tab. Kept intentionally small: list-by-app + basic
 * writes. Ranking / recall / hit-count bumping happens in a separate pass when
 * chat responses actually consult annotations.
 */
public class AppAnnotationService {

    private final AppAnnotationMapper mapper;

    public AppAnnotationService(AppAnnotationMapper mapper) {
        this.mapper = mapper;
    }

    public List<AppAnnotationEntity> list(String appId) {
        return mapper.selectList(new LambdaQueryWrapper<AppAnnotationEntity>()
                .eq(AppAnnotationEntity::getAppId, appId)
                .orderByDesc(AppAnnotationEntity::getUpdatedAt));
    }

    public AppAnnotationEntity require(String id) {
        AppAnnotationEntity e = mapper.selectById(id);
        if (e == null) {
            throw new AgentException("annotation_not_found", "Annotation not found: " + id, null);
        }
        return e;
    }

    @Transactional
    public AppAnnotationEntity create(AppAnnotationEntity entity) {
        if (entity.getHitCount() == null) entity.setHitCount(0);
        if (entity.getEnabled() == null) entity.setEnabled(true);
        mapper.insert(entity);
        return entity;
    }

    @Transactional
    public AppAnnotationEntity update(String id, AppAnnotationEntity patch) {
        AppAnnotationEntity existing = require(id);
        if (patch.getQuestion() != null) existing.setQuestion(patch.getQuestion());
        if (patch.getContent() != null) existing.setContent(patch.getContent());
        if (patch.getEnabled() != null) existing.setEnabled(patch.getEnabled());
        mapper.updateById(existing);
        return existing;
    }

    @Transactional
    public void delete(String id) {
        mapper.deleteById(id);
    }

    /**
     * Increment the hit counter — called by the chat runtime when an annotation
     * actually serves a query. Wired up in a future pass; the endpoint exists
     * so the frontend can display accurate counts today.
     */
    @Transactional
    public void bumpHit(String id) {
        AppAnnotationEntity existing = require(id);
        existing.setHitCount(existing.getHitCount() == null ? 1 : existing.getHitCount() + 1);
        mapper.updateById(existing);
    }
}
