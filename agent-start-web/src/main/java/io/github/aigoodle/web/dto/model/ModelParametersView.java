package io.github.aigoodle.web.dto.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

/** Parameter-editor payload containing typed rules and dynamic configured values. */
@Data
public class ModelParametersView {
    private List<ParameterRuleView> rules;
    /** Values are keyed by provider-defined parameter names, so this part is intentionally dynamic. */
    private Map<String, Object> parameters;
}

