package io.github.aigoodle.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.agent.entity.AppSiteEntity;
import io.github.aigoodle.agent.mapper.AppSiteMapper;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Manage the published-site config row per app. Auto-mints a public {@code code}
 * (short URL slug) on first save so the widget URL is stable across restarts.
 */
public class AppSiteService {

    private static final SecureRandom RNG = new SecureRandom();

    private final AppSiteMapper mapper;

    public AppSiteService(AppSiteMapper mapper) {
        this.mapper = mapper;
    }

    public AppSiteEntity getByApp(String appId) {
        AppSiteEntity existing = mapper.selectOne(new LambdaQueryWrapper<AppSiteEntity>()
                .eq(AppSiteEntity::getAppId, appId)
                .last("LIMIT 1"));
        if (existing != null) return existing;
        AppSiteEntity fallback = new AppSiteEntity();
        fallback.setAppId(appId);
        fallback.setStatus("normal");
        return fallback;
    }

    @Transactional
    public AppSiteEntity save(String appId, AppSiteEntity patch) {
        AppSiteEntity existing = mapper.selectOne(new LambdaQueryWrapper<AppSiteEntity>()
                .eq(AppSiteEntity::getAppId, appId)
                .last("LIMIT 1"));
        if (existing == null) {
            patch.setAppId(appId);
            if (patch.getCode() == null || patch.getCode().isBlank()) patch.setCode(shortCode());
            if (patch.getStatus() == null) patch.setStatus("normal");
            mapper.insert(patch);
            return patch;
        }
        if (patch.getTitle() != null) existing.setTitle(patch.getTitle());
        if (patch.getIcon() != null) existing.setIcon(patch.getIcon());
        if (patch.getIconBackground() != null) existing.setIconBackground(patch.getIconBackground());
        if (patch.getIconType() != null) existing.setIconType(patch.getIconType());
        if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
        if (patch.getDefaultLanguage() != null) existing.setDefaultLanguage(patch.getDefaultLanguage());
        if (patch.getCopyright() != null) existing.setCopyright(patch.getCopyright());
        if (patch.getPrivacyPolicy() != null) existing.setPrivacyPolicy(patch.getPrivacyPolicy());
        if (patch.getCustomDisclaimer() != null) existing.setCustomDisclaimer(patch.getCustomDisclaimer());
        if (patch.getChatColorTheme() != null) existing.setChatColorTheme(patch.getChatColorTheme());
        if (patch.getChatColorThemeInverted() != null) existing.setChatColorThemeInverted(patch.getChatColorThemeInverted());
        if (patch.getShowWorkflowSteps() != null) existing.setShowWorkflowSteps(patch.getShowWorkflowSteps());
        if (patch.getUseIconAsAnswerIcon() != null) existing.setUseIconAsAnswerIcon(patch.getUseIconAsAnswerIcon());
        if (patch.getStatus() != null) existing.setStatus(patch.getStatus());
        mapper.updateById(existing);
        return existing;
    }

    private static String shortCode() {
        byte[] bytes = new byte[9];
        RNG.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
