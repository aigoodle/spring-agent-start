package io.github.aigoodle.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.agent.entity.AppAnnotationSettingEntity;
import io.github.aigoodle.agent.mapper.AppAnnotationSettingMapper;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manage the single annotation-retrieval configuration row per app. Reads
 * return a fresh default row (not persisted) so the frontend can render the
 * form even before the first save.
 */
public class AppAnnotationSettingService {

    private static final float DEFAULT_THRESHOLD = 0.8f;

    private final AppAnnotationSettingMapper mapper;

    public AppAnnotationSettingService(AppAnnotationSettingMapper mapper) {
        this.mapper = mapper;
    }

    public AppAnnotationSettingEntity getByApp(String appId) {
        AppAnnotationSettingEntity existing = mapper.selectOne(
                new LambdaQueryWrapper<AppAnnotationSettingEntity>()
                        .eq(AppAnnotationSettingEntity::getAppId, appId)
                        .last("LIMIT 1"));
        if (existing != null) return existing;
        AppAnnotationSettingEntity fallback = new AppAnnotationSettingEntity();
        fallback.setAppId(appId);
        fallback.setEnabled(false);
        fallback.setScoreThreshold(DEFAULT_THRESHOLD);
        return fallback;
    }

    /** Upsert — settings are effectively singletons per app. */
    @Transactional
    public AppAnnotationSettingEntity save(String appId, AppAnnotationSettingEntity patch) {
        AppAnnotationSettingEntity existing = mapper.selectOne(
                new LambdaQueryWrapper<AppAnnotationSettingEntity>()
                        .eq(AppAnnotationSettingEntity::getAppId, appId)
                        .last("LIMIT 1"));
        if (existing == null) {
            patch.setAppId(appId);
            if (patch.getScoreThreshold() == null) patch.setScoreThreshold(DEFAULT_THRESHOLD);
            if (patch.getEnabled() == null) patch.setEnabled(false);
            mapper.insert(patch);
            return patch;
        }
        if (patch.getScoreThreshold() != null) existing.setScoreThreshold(patch.getScoreThreshold());
        if (patch.getEmbeddingModelId() != null) existing.setEmbeddingModelId(patch.getEmbeddingModelId());
        if (patch.getEnabled() != null) existing.setEnabled(patch.getEnabled());
        mapper.updateById(existing);
        return existing;
    }

    @Transactional
    public AppAnnotationSettingEntity setEnabled(String appId, boolean enabled) {
        AppAnnotationSettingEntity setting = getByApp(appId);
        setting.setEnabled(enabled);
        return save(appId, setting);
    }
}
