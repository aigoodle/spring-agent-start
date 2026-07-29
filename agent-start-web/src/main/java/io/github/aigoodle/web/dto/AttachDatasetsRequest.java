package io.github.aigoodle.web.dto;

import lombok.Data;

import java.util.List;

/** Body payload for {@code POST/PUT /apps/{appId}/datasets}. */
@Data
public class AttachDatasetsRequest {
    /** Dataset ids to attach (POST) or the full replacement set (PUT). */
    private List<String> datasetIds;
}
