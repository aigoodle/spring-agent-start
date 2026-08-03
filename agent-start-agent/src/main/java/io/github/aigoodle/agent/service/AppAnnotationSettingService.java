package io.github.aigoodle.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.agent.entity.AppAnnotationSettingEntity;
import io.github.aigoodle.agent.mapper.AppAnnotationSettingMapper;
import org.springframework.transaction.annotation.Transactional;

/** Manages the single annotation-retrieval configuration owned by an application. */
public class AppAnnotationSettingService {

    private static final float DEFAULT_SCORE_THRESHOLD = 0.8f;

    private final AppAnnotationSettingMapper settingMapper;

    public AppAnnotationSettingService(AppAnnotationSettingMapper settingMapper) {
        this.settingMapper = settingMapper;
    }

    public AppAnnotationSettingEntity getByApp(String appId) {
        AppAnnotationSettingEntity setting = findByApp(appId);
        return setting != null ? setting : defaultSetting(appId);
    }

    @Transactional
    public AppAnnotationSettingEntity save(String appId, AppAnnotationSettingEntity updates) {
        AppAnnotationSettingEntity setting = findByApp(appId);
        if (setting == null) {
            setting = defaultSetting(appId);
            applyUpdates(setting, updates);
            settingMapper.insert(setting);
            return setting;
        }

        applyUpdates(setting, updates);
        settingMapper.updateById(setting);
        return setting;
    }

    @Transactional
    public AppAnnotationSettingEntity setEnabled(String appId, boolean enabled) {
        AppAnnotationSettingEntity setting = findByApp(appId);
        if (setting == null) {
            setting = defaultSetting(appId);
            setting.setEnabled(enabled);
            settingMapper.insert(setting);
            return setting;
        }

        setting.setEnabled(enabled);
        settingMapper.updateById(setting);
        return setting;
    }

    private AppAnnotationSettingEntity findByApp(String appId) {
        return settingMapper.selectOne(new LambdaQueryWrapper<AppAnnotationSettingEntity>()
                .eq(AppAnnotationSettingEntity::getAppId, appId)
                .last("LIMIT 1"));
    }

    private static AppAnnotationSettingEntity defaultSetting(String appId) {
        AppAnnotationSettingEntity setting = new AppAnnotationSettingEntity();
        setting.setAppId(appId);
        setting.setEnabled(false);
        setting.setScoreThreshold(DEFAULT_SCORE_THRESHOLD);
        return setting;
    }

    private static void applyUpdates(AppAnnotationSettingEntity setting,
                                     AppAnnotationSettingEntity updates) {
        if (updates.getScoreThreshold() != null) {
            setting.setScoreThreshold(updates.getScoreThreshold());
        }
        if (updates.getEmbeddingModelId() != null) {
            setting.setEmbeddingModelId(updates.getEmbeddingModelId());
        }
        if (updates.getEnabled() != null) {
            setting.setEnabled(updates.getEnabled());
        }
    }
}
