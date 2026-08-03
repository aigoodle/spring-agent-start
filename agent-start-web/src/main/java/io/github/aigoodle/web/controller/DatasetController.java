package io.github.aigoodle.web.controller;

import io.github.aigoodle.knowledge.entity.DatasetEntity;
import io.github.aigoodle.knowledge.entity.HitTestingLogEntity;
import io.github.aigoodle.knowledge.retrieve.RetrievedSegment;
import io.github.aigoodle.knowledge.service.CreateDatasetRequest;
import io.github.aigoodle.knowledge.service.DatasetService;
import io.github.aigoodle.knowledge.service.KnowledgeService;
import io.github.aigoodle.knowledge.service.UpdateDatasetRequest;
import io.github.aigoodle.web.common.ApiResponse;
import io.github.aigoodle.web.dto.RetrieveRequestDto;
import io.github.aigoodle.web.support.RetrievalRequestMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Dataset metadata and retrieval endpoints. */
@RestController
@ConditionalOnBean(KnowledgeService.class)
@RequestMapping("${spring-agent.web.base-path:}/datasets")
public class DatasetController {

    private final DatasetService datasetService;
    private final KnowledgeService knowledgeService;

    public DatasetController(DatasetService datasetService, KnowledgeService knowledgeService) {
        this.datasetService = datasetService;
        this.knowledgeService = knowledgeService;
    }

    @GetMapping
    public ApiResponse<List<DatasetEntity>> list(@RequestParam(required = false) String tenantId) {
        return ApiResponse.ok(datasetService.list(tenantId));
    }

    @GetMapping("/{id}")
    public ApiResponse<DatasetEntity> get(@PathVariable String id) {
        return ApiResponse.ok(datasetService.require(id));
    }

    @PostMapping
    public ApiResponse<DatasetEntity> create(@RequestBody CreateDatasetRequest request) {
        return ApiResponse.ok(datasetService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<DatasetEntity> update(@PathVariable String id,
                                             @RequestBody UpdateDatasetRequest request) {
        return ApiResponse.ok(datasetService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        datasetService.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/hit-testing/history")
    public ApiResponse<List<HitTestingLogEntity>> hitTestingHistory(
            @PathVariable String id, @RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.ok(knowledgeService.listHitTestingHistory(id, limit));
    }

    @PostMapping("/{id}/retrieve")
    public ApiResponse<List<RetrievedSegment>> retrieve(
            @PathVariable String id, @RequestBody RetrieveRequestDto request) {
        return ApiResponse.ok(knowledgeService.retrieve(id, RetrievalRequestMapper.from(request)));
    }
}
