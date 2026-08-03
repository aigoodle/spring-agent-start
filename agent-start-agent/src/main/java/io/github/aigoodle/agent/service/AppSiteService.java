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

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AppSiteMapper siteMapper;

    public AppSiteService(AppSiteMapper siteMapper) {
        this.siteMapper = siteMapper;
    }

    public AppSiteEntity getByApp(String appId) {
        AppSiteEntity existingSite = siteMapper.selectOne(new LambdaQueryWrapper<AppSiteEntity>()
                .eq(AppSiteEntity::getAppId, appId)
                .last("LIMIT 1"));
        if (existingSite != null) {
            return existingSite;
        }
        AppSiteEntity defaultSite = new AppSiteEntity();
        defaultSite.setAppId(appId);
        defaultSite.setStatus("normal");
        return defaultSite;
    }

    @Transactional
    public AppSiteEntity save(String appId, AppSiteEntity siteUpdates) {
        AppSiteEntity existingSite = siteMapper.selectOne(new LambdaQueryWrapper<AppSiteEntity>()
                .eq(AppSiteEntity::getAppId, appId)
                .last("LIMIT 1"));
        if (existingSite == null) {
            initializeNewSite(appId, siteUpdates);
            siteMapper.insert(siteUpdates);
            return siteUpdates;
        }
        applyUpdates(existingSite, siteUpdates);
        siteMapper.updateById(existingSite);
        return existingSite;
    }

    private static void initializeNewSite(String appId, AppSiteEntity newSite) {
        newSite.setAppId(appId);
        if (newSite.getCode() == null || newSite.getCode().isBlank()) {
            newSite.setCode(generateShortCode());
        }
        if (newSite.getStatus() == null) {
            newSite.setStatus("normal");
        }
    }

    private static void applyUpdates(AppSiteEntity site, AppSiteEntity updates) {
        if (updates.getTitle() != null) {
            site.setTitle(updates.getTitle());
        }
        if (updates.getIcon() != null) {
            site.setIcon(updates.getIcon());
        }
        if (updates.getIconBackground() != null) {
            site.setIconBackground(updates.getIconBackground());
        }
        if (updates.getIconType() != null) {
            site.setIconType(updates.getIconType());
        }
        if (updates.getDescription() != null) {
            site.setDescription(updates.getDescription());
        }
        if (updates.getDefaultLanguage() != null) {
            site.setDefaultLanguage(updates.getDefaultLanguage());
        }
        if (updates.getCopyright() != null) {
            site.setCopyright(updates.getCopyright());
        }
        if (updates.getPrivacyPolicy() != null) {
            site.setPrivacyPolicy(updates.getPrivacyPolicy());
        }
        if (updates.getCustomDisclaimer() != null) {
            site.setCustomDisclaimer(updates.getCustomDisclaimer());
        }
        if (updates.getChatColorTheme() != null) {
            site.setChatColorTheme(updates.getChatColorTheme());
        }
        if (updates.getChatColorThemeInverted() != null) {
            site.setChatColorThemeInverted(updates.getChatColorThemeInverted());
        }
        if (updates.getShowWorkflowSteps() != null) {
            site.setShowWorkflowSteps(updates.getShowWorkflowSteps());
        }
        if (updates.getUseIconAsAnswerIcon() != null) {
            site.setUseIconAsAnswerIcon(updates.getUseIconAsAnswerIcon());
        }
        if (updates.getStatus() != null) {
            site.setStatus(updates.getStatus());
        }
    }

    private static String generateShortCode() {
        byte[] randomBytes = new byte[9];
        SECURE_RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}
