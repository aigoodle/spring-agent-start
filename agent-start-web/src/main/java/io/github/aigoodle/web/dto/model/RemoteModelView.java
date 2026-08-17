package io.github.aigoodle.web.dto.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.aigoodle.model.enums.ModelFeature;
import io.github.aigoodle.model.enums.ModelType;
import lombok.Data;

import java.util.Set;

/** Stable API representation of a model discovered from a provider's live catalog. */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RemoteModelView {
    private String modelId;
    private String label;
    private ModelType modelType;
    private Integer contextLength;
    private Integer dimensions;
    private Set<ModelFeature> features;
    private String ownedBy;
    private boolean typeInferred;
}

