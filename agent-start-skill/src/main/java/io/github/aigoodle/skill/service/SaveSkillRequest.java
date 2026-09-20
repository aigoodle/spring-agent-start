package io.github.aigoodle.skill.service;

import lombok.Data;
import java.util.List;

@Data
public class SaveSkillRequest {
    private String code;
    private String name;
    private String description;
    private String instructions;
    private String status;
    private List<String> toolNames = List.of();
}
