package io.github.aigoodle.web.controller;

import io.github.aigoodle.knowledge.entity.SegmentEntity;
import io.github.aigoodle.knowledge.service.KnowledgeService;
import io.github.aigoodle.web.common.ApiResponse;
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

/** Segment inspection and editing endpoints for knowledge documents. */
@RestController
@ConditionalOnBean(KnowledgeService.class)
@RequestMapping("${spring-agent.web.base-path:}/datasets")
public class DatasetSegmentController {

    private final KnowledgeService knowledgeService;

    public DatasetSegmentController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @GetMapping("/{id}/documents/{documentId}/segments")
    public ApiResponse<List<SegmentEntity>> listSegments(
            @PathVariable String id,
            @PathVariable String documentId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return ApiResponse.ok(knowledgeService.listSegments(documentId, page, pageSize));
    }

    @PostMapping("/{id}/documents/{documentId}/segments")
    public ApiResponse<SegmentEntity> appendSegment(
            @PathVariable String id,
            @PathVariable String documentId,
            @RequestBody UpdateSegmentRequest request) {
        SegmentEntity segment = knowledgeService.appendSegment(id, documentId, request.content());
        return segment == null
                ? ApiResponse.error("document_not_found", "Document not found: " + documentId)
                : ApiResponse.ok(segment);
    }

    @PutMapping("/{id}/documents/{documentId}/segments/{segmentId}")
    public ApiResponse<SegmentEntity> updateSegment(
            @PathVariable String id,
            @PathVariable String documentId,
            @PathVariable String segmentId,
            @RequestBody UpdateSegmentRequest request) {
        SegmentEntity segment = knowledgeService.updateSegment(id, segmentId, request.content());
        return segment == null
                ? ApiResponse.error("segment_not_found", "Segment not found: " + segmentId)
                : ApiResponse.ok(segment);
    }

    @DeleteMapping("/{id}/documents/{documentId}/segments/{segmentId}")
    public ApiResponse<Void> deleteSegment(
            @PathVariable String id,
            @PathVariable String documentId,
            @PathVariable String segmentId) {
        knowledgeService.deleteSegment(id, segmentId);
        return ApiResponse.ok();
    }

    @PutMapping("/{id}/documents/{documentId}/segments/{segmentId}/enabled")
    public ApiResponse<SegmentEntity> setSegmentEnabled(
            @PathVariable String id,
            @PathVariable String documentId,
            @PathVariable String segmentId,
            @RequestBody EnabledRequest request) {
        boolean enabled = Boolean.TRUE.equals(request.enabled());
        SegmentEntity segment = knowledgeService.setSegmentEnabled(id, segmentId, enabled);
        return segment == null
                ? ApiResponse.error("segment_not_found", "Segment not found: " + segmentId)
                : ApiResponse.ok(segment);
    }

    public record UpdateSegmentRequest(String content) {
    }

    public record EnabledRequest(Boolean enabled) {
    }
}
