package io.github.aigoodle.web.dto;

import lombok.Data;

import java.util.List;

/** Body for POST/PUT on {@code /prompt-templates}. */
@Data
public class PromptTemplateRequest {

    private String tenantId;
    private String name;
    private String category;
    private String description;
    private String content;
    private List<String> tags;
}
