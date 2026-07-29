package io.github.aigoodle.model.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.model.entity.PromptTemplateEntity;
import io.github.aigoodle.model.mapper.PromptTemplateMapper;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CRUD for reusable prompt templates plus a lightweight {@link #render(String, Map)}
 * helper — the same {@code {{#name#}}} placeholder syntax used across the workflow
 * engine, so templates round-trip cleanly.
 */
public class PromptTemplateService {

    private static final Pattern REF = Pattern.compile("\\{\\{#\\s*([a-zA-Z0-9_\\-.]+)\\s*#}}");

    private final PromptTemplateMapper mapper;

    public PromptTemplateService(PromptTemplateMapper mapper) {
        this.mapper = mapper;
    }

    public PromptTemplateEntity create(String tenantId, String name, String category,
                                        String description, String content, List<String> tags) {
        PromptTemplateEntity e = new PromptTemplateEntity();
        e.setTenantId(tenantId == null || tenantId.isBlank() ? "default" : tenantId);
        e.setName(name);
        e.setCategory(category);
        e.setDescription(description);
        e.setContent(content);
        e.setTagsJson(tags == null ? "[]" : JsonUtils.toJson(tags));
        mapper.insert(e);
        return e;
    }

    public PromptTemplateEntity update(String id, String name, String category,
                                        String description, String content, List<String> tags) {
        PromptTemplateEntity e = require(id);
        if (name != null) e.setName(name);
        if (category != null) e.setCategory(category);
        if (description != null) e.setDescription(description);
        if (content != null) e.setContent(content);
        if (tags != null) e.setTagsJson(JsonUtils.toJson(tags));
        mapper.updateById(e);
        return e;
    }

    public void delete(String id) {
        mapper.deleteById(id);
    }

    public PromptTemplateEntity require(String id) {
        PromptTemplateEntity e = mapper.selectById(id);
        if (e == null) {
            throw new AgentException("prompt_template_not_found", "Prompt template not found: " + id, null);
        }
        return e;
    }

    public PromptTemplateEntity get(String id) {
        return mapper.selectById(id);
    }

    public List<PromptTemplateEntity> list(String tenantId, String category) {
        LambdaQueryWrapper<PromptTemplateEntity> q = new LambdaQueryWrapper<PromptTemplateEntity>()
                .eq(PromptTemplateEntity::getTenantId, tenantId == null ? "default" : tenantId)
                .orderByDesc(PromptTemplateEntity::getUpdatedAt);
        if (category != null && !category.isBlank()) {
            q.eq(PromptTemplateEntity::getCategory, category);
        }
        return mapper.selectList(q);
    }

    public long count(String tenantId) {
        return mapper.selectCount(new LambdaQueryWrapper<PromptTemplateEntity>()
                .eq(PromptTemplateEntity::getTenantId, tenantId == null ? "default" : tenantId));
    }

    /**
     * Seed a small set of starter templates when the table is empty for {@code tenantId}
     * — a nudge for first-time users so the Prompt library page isn't a wall of empty
     * state on first boot. Safe to call repeatedly; only writes when the table is empty.
     */
    public void seedStartersIfEmpty(String tenantId) {
        String tid = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        if (count(tid) > 0) {
            return;
        }
        create(tid, "summarize-en", "summarization",
                "Concise English summary in one paragraph.",
                "You are a concise summarizer. Read the passage and write ONE paragraph "
                        + "capturing the essential points.\n\nPassage:\n{{#input#}}",
                java.util.List.of("summary", "en"));
        create(tid, "classify-intent", "classifier",
                "Route a user message into an intent id (uses {{#categories#}}).",
                "You are a strict intent classifier. Pick ONE category id from the list.\n"
                        + "Reply with ONLY the id.\nCategories:\n{{#categories#}}",
                java.util.List.of("classifier"));
        create(tid, "extract-json", "extraction",
                "Extract structured JSON matching a supplied schema.",
                "Extract the fields defined by the JSON schema from the input. Reply with "
                        + "ONLY the JSON object, no code fences, no prose. Use null when a field "
                        + "is missing.\n\nSchema:\n{{#schema#}}\n\nInput:\n{{#input#}}",
                java.util.List.of("json", "extraction"));
    }

    /** Extract the referenced variable names — for previewing what a template needs. */
    public List<String> variablesOf(String content) {
        if (content == null) {
            return List.of();
        }
        List<String> vars = new java.util.ArrayList<>();
        Matcher m = REF.matcher(content);
        while (m.find()) {
            String v = m.group(1);
            if (!vars.contains(v)) {
                vars.add(v);
            }
        }
        return vars;
    }

    /** Render a template against a variable map. Missing keys resolve to empty. */
    public String render(String content, Map<String, Object> variables) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        Matcher m = REF.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String path = m.group(1);
            Object v = variables == null ? null : variables.get(path);
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
