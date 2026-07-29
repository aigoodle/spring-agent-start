package io.github.aigoodle.web.controller;

import io.github.aigoodle.agent.service.AppDatasetService;
import io.github.aigoodle.web.common.ApiResponse;
import io.github.aigoodle.web.dto.AttachDatasetsRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Manage the knowledge bases attached to an app. Powers the "知识库" tab in
 * the app design drawer: list currently attached datasets, add one, remove
 * one, or replace the whole list with a single "save" call.
 * <p>
 * Wired only when {@link AppDatasetService} is present — which itself only
 * activates when the knowledge module is on the classpath. When absent the
 * console can still edit {@code datasetIds} through the plain agent update
 * endpoint (no validation, no hydration).
 */
@RestController
@ConditionalOnBean(AppDatasetService.class)
@RequestMapping("${spring-agent.web.base-path:}/apps/{appId}/datasets")
public class AppDatasetController {

    private final AppDatasetService service;

    public AppDatasetController(AppDatasetService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<AppDatasetService.AttachedDataset>> list(@PathVariable String appId) {
        return ApiResponse.ok(service.list(appId));
    }

    /** Additive — merges the supplied ids with the existing attached set. */
    @PostMapping
    public ApiResponse<List<AppDatasetService.AttachedDataset>> attach(@PathVariable String appId,
                                                                       @RequestBody AttachDatasetsRequest req) {
        return ApiResponse.ok(service.attach(appId, req.getDatasetIds()));
    }

    /** Replace the whole attached list — used by the "save" button on the settings panel. */
    @PutMapping
    public ApiResponse<List<AppDatasetService.AttachedDataset>> replace(@PathVariable String appId,
                                                                        @RequestBody AttachDatasetsRequest req) {
        return ApiResponse.ok(service.replace(appId, req.getDatasetIds()));
    }

    @DeleteMapping("/{datasetId}")
    public ApiResponse<List<AppDatasetService.AttachedDataset>> detach(@PathVariable String appId,
                                                                       @PathVariable String datasetId) {
        return ApiResponse.ok(service.detach(appId, datasetId));
    }
}
