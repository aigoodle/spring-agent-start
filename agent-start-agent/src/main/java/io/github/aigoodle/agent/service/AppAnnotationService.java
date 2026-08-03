package io.github.aigoodle.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.agent.entity.AppAnnotationEntity;
import io.github.aigoodle.agent.mapper.AppAnnotationMapper;
import io.github.aigoodle.common.exception.AgentException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Manages the curated question-and-answer pairs owned by an application.
 * Application ownership is part of every mutation so a route scoped to one
 * application cannot accidentally modify another application's annotation.
 */
public class AppAnnotationService {

    private final AppAnnotationMapper annotationMapper;

    public AppAnnotationService(AppAnnotationMapper annotationMapper) {
        this.annotationMapper = annotationMapper;
    }

    public List<AppAnnotationEntity> list(String appId) {
        return annotationMapper.selectList(new LambdaQueryWrapper<AppAnnotationEntity>()
                .eq(AppAnnotationEntity::getAppId, appId)
                .orderByDesc(AppAnnotationEntity::getUpdatedAt));
    }

    public AppAnnotationEntity require(String appId, String annotationId) {
        AppAnnotationEntity annotation = annotationMapper.selectOne(
                new LambdaQueryWrapper<AppAnnotationEntity>()
                        .eq(AppAnnotationEntity::getId, annotationId)
                        .eq(AppAnnotationEntity::getAppId, appId)
                        .last("LIMIT 1"));
        if (annotation == null) {
            throw new AgentException("annotation_not_found",
                    "Annotation not found: " + annotationId, null);
        }
        return annotation;
    }

    @Transactional
    public AppAnnotationEntity create(AppAnnotationEntity annotation) {
        if (annotation.getHitCount() == null) {
            annotation.setHitCount(0);
        }
        if (annotation.getEnabled() == null) {
            annotation.setEnabled(true);
        }
        annotationMapper.insert(annotation);
        return annotation;
    }

    @Transactional
    public AppAnnotationEntity update(String appId, String annotationId,
                                      AppAnnotationEntity updates) {
        AppAnnotationEntity annotation = require(appId, annotationId);
        if (updates.getQuestion() != null) {
            annotation.setQuestion(updates.getQuestion());
        }
        if (updates.getContent() != null) {
            annotation.setContent(updates.getContent());
        }
        if (updates.getEnabled() != null) {
            annotation.setEnabled(updates.getEnabled());
        }
        annotationMapper.updateById(annotation);
        return annotation;
    }

    @Transactional
    public void delete(String appId, String annotationId) {
        AppAnnotationEntity annotation = require(appId, annotationId);
        annotationMapper.deleteById(annotation.getId());
    }

    /** Increments the number of times an annotation has served a response. */
    @Transactional
    public void recordHit(String appId, String annotationId) {
        AppAnnotationEntity annotation = require(appId, annotationId);
        int currentHitCount = annotation.getHitCount() == null ? 0 : annotation.getHitCount();
        annotation.setHitCount(currentHitCount + 1);
        annotationMapper.updateById(annotation);
    }
}
