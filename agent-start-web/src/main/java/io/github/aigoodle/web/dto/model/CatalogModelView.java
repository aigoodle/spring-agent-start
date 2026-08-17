package io.github.aigoodle.web.dto.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.aigoodle.model.enums.ModelFeature;
import io.github.aigoodle.model.enums.ModelType;
import lombok.Data;

import java.util.List;
import java.util.Set;

/** A predefined or tenant-custom model row shown in a provider catalog. */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CatalogModelView {
    private String id;
    private String model;
    private String label;
    private ModelType modelType;
    private String credentialId;
    private Integer contextLength;
    private Integer dimensions;
    private Set<ModelFeature> features;
    private List<ParameterRuleView> parameterRules;
    private String source;
    private Boolean enabled;
    private Boolean loadBalancingEnabled;
    private Boolean isDefault;
}

