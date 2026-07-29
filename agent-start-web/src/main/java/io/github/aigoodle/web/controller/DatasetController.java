package io.github.aigoodle.web.controller;

import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.knowledge.config.ProcessRule;
import io.github.aigoodle.knowledge.entity.DatasetEntity;
import io.github.aigoodle.knowledge.entity.HitTestingLogEntity;
import io.github.aigoodle.knowledge.entity.KnowledgeDocumentEntity;
import io.github.aigoodle.knowledge.entity.SegmentEntity;
import io.github.aigoodle.knowledge.retrieve.RetrievalRequest;
import io.github.aigoodle.knowledge.retrieve.RetrievedSegment;
import io.github.aigoodle.knowledge.service.CreateDatasetRequest;
import io.github.aigoodle.knowledge.service.DatasetService;
import io.github.aigoodle.knowledge.service.KnowledgeService;
import io.github.aigoodle.knowledge.service.UpdateDatasetRequest;
import io.github.aigoodle.web.common.ApiResponse;
import io.github.aigoodle.web.dto.RetrieveRequestDto;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * REST facade over {@link DatasetService} + {@link KnowledgeService}. Provides the
 * endpoints the bundled web frontend uses:
 * <ul>
 *   <li>list / create / delete datasets</li>
 *   <li>upload text / markdown / files as documents</li>
 *   <li>list / delete documents inside a dataset</li>
 *   <li>run a retrieval (a.k.a. hit testing) against a dataset</li>
 * </ul>
 * Only wired when the knowledge module is on the classpath.
 */
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

    // ---------------------------------------------------------------- datasets

    @GetMapping
    public ApiResponse<List<DatasetEntity>> list(@RequestParam(required = false) String tenantId) {
        return ApiResponse.ok(datasetService.list(tenantId));
    }

    @GetMapping("/{id}")
    public ApiResponse<DatasetEntity> get(@PathVariable String id) {
        return ApiResponse.ok(datasetService.require(id));
    }

    @PostMapping
    public ApiResponse<DatasetEntity> create(@RequestBody CreateDatasetRequest req) {
        return ApiResponse.ok(datasetService.create(req));
    }

    /** Edit dataset metadata + retrieval/chunk settings — Dify "dataset settings" dialog. */
    @PutMapping("/{id}")
    public ApiResponse<DatasetEntity> update(@PathVariable String id,
                                             @RequestBody UpdateDatasetRequest req) {
        return ApiResponse.ok(datasetService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        datasetService.delete(id);
        return ApiResponse.ok();
    }

    // --------------------------------------------------------------- documents

    @GetMapping("/{id}/documents")
    public ApiResponse<List<KnowledgeDocumentEntity>> listDocuments(@PathVariable String id) {
        return ApiResponse.ok(knowledgeService.listDocuments(id));
    }

    /** Single-document detail view (name, status, error message, counters). */
    @GetMapping("/{id}/documents/{documentId}")
    public ApiResponse<KnowledgeDocumentEntity> getDocument(@PathVariable String id,
                                                            @PathVariable String documentId) {
        KnowledgeDocumentEntity doc = knowledgeService.getDocument(documentId);
        if (doc == null) {
            return ApiResponse.error("document_not_found", "Document not found: " + documentId);
        }
        return ApiResponse.ok(doc);
    }

    /** Paginated segments (chunks) of a document — for the "chunks" tab. */
    @GetMapping("/{id}/documents/{documentId}/segments")
    public ApiResponse<List<SegmentEntity>> listSegments(@PathVariable String id,
                                                         @PathVariable String documentId,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "50") int pageSize) {
        return ApiResponse.ok(knowledgeService.listSegments(documentId, page, pageSize));
    }

    /** Ingest inline text (short notes, form input, pasted knowledge). */
    @PostMapping("/{id}/documents/text")
    public ApiResponse<KnowledgeDocumentEntity> addText(@PathVariable String id,
                                                        @RequestBody AddTextRequest req) {
        return ApiResponse.ok(knowledgeService.addText(id, req.name(), req.text()));
    }

    /** Ingest markdown; markdown chunker will preserve heading structure. */
    @PostMapping("/{id}/documents/markdown")
    public ApiResponse<KnowledgeDocumentEntity> addMarkdown(@PathVariable String id,
                                                            @RequestBody AddTextRequest req) {
        return ApiResponse.ok(knowledgeService.addMarkdown(id, req.name(), req.text()));
    }

    /**
     * Upload a binary file (PDF, DOCX, PPTX, XLSX, HTML, plain text, …). Reader is
     * chosen from the extension via {@code DocumentReaderRegistry}.
     */
    @PostMapping("/{id}/documents/upload")
    public ApiResponse<KnowledgeDocumentEntity> upload(@PathVariable String id,
                                                       @RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ApiResponse.error("file_required", "An uploaded file is required");
        }
        String filename = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? "upload.bin" : file.getOriginalFilename();
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (java.io.IOException e) {
            throw new io.github.aigoodle.common.exception.AgentException(
                    "upload_read_failed", "Failed to read upload: " + e.getMessage(), e);
        }
        return ApiResponse.ok(knowledgeService.addFile(id, filename, bytes));
    }

    @DeleteMapping("/{id}/documents/{documentId}")
    public ApiResponse<Void> deleteDocument(@PathVariable String id, @PathVariable String documentId) {
        knowledgeService.deleteDocument(documentId);
        return ApiResponse.ok();
    }

    /**
     * Preview how a file would be chunked under a given {@link ProcessRule},
     * without touching the database or the vector store. The wizard's
     * "文本分段与清洗" step calls this so the preview it shows the user is
     * exactly what real ingestion would produce — no more mock previews that
     * diverge from the persisted result.
     *
     * <p>Not scoped to a dataset id because the wizard runs before creating
     * one; sits at {@code /datasets/preview-chunks} instead of
     * {@code /datasets/{id}/…}. Multipart body is
     * {@code file} (the raw upload) + {@code rule} (a JSON-encoded
     * {@link ProcessRule}). {@code limit} query param caps the returned
     * chunks so huge documents don't blow up the response.</p>
     */
    @PostMapping(value = "/preview-chunks",
            consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<KnowledgeService.ChunkPreview> previewChunks(
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "rule", required = false) String ruleJson,
            @RequestParam(value = "limit", defaultValue = "10") int limit) {
        if (file == null || file.isEmpty()) {
            return ApiResponse.error("file_required", "An uploaded file is required");
        }
        ProcessRule rule;
        try {
            rule = ruleJson == null || ruleJson.isBlank()
                    ? ProcessRule.naive()
                    : JsonUtils.parse(ruleJson, ProcessRule.class);
        } catch (Exception ex) {
            return ApiResponse.error("rule_invalid",
                    "'rule' is not a valid ProcessRule JSON: " + ex.getMessage());
        }
        String filename = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? "upload.bin" : file.getOriginalFilename();
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (java.io.IOException e) {
            throw new io.github.aigoodle.common.exception.AgentException(
                    "upload_read_failed", "Failed to read upload: " + e.getMessage(), e);
        }
        return ApiResponse.ok(knowledgeService.previewChunks(bytes, filename, rule, limit));
    }

    /**
     * Re-embed every enabled segment of a document — the primary use case is
     * "I swapped the embedding model on this dataset, please refresh the vectors".
     */
    @PostMapping("/{id}/documents/{documentId}/reindex")
    public ApiResponse<Map<String, Object>> reindex(@PathVariable String id,
                                                    @PathVariable String documentId) {
        int count = knowledgeService.reindexDocument(id, documentId);
        return ApiResponse.ok(Map.of("segmentCount", count));
    }

    // ---------------------------------------------------------------- segments

    /** Append a hand-authored chunk to a document — matches Dify's SegmentAddApi. */
    @PostMapping("/{id}/documents/{documentId}/segments")
    public ApiResponse<SegmentEntity> appendSegment(@PathVariable String id,
                                                    @PathVariable String documentId,
                                                    @RequestBody UpdateSegmentRequest req) {
        SegmentEntity created = knowledgeService.appendSegment(id, documentId, req.content());
        if (created == null) {
            return ApiResponse.error("document_not_found", "Document not found: " + documentId);
        }
        return ApiResponse.ok(created);
    }

    /** Edit a chunk's content — re-embeds and swaps the vector so the change is searchable. */
    @PutMapping("/{id}/documents/{documentId}/segments/{segmentId}")
    public ApiResponse<SegmentEntity> updateSegment(@PathVariable String id,
                                                    @PathVariable String documentId,
                                                    @PathVariable String segmentId,
                                                    @RequestBody UpdateSegmentRequest req) {
        SegmentEntity updated = knowledgeService.updateSegment(id, segmentId, req.content());
        if (updated == null) {
            return ApiResponse.error("segment_not_found", "Segment not found: " + segmentId);
        }
        return ApiResponse.ok(updated);
    }

    /** Delete a single chunk without disturbing its siblings. */
    @DeleteMapping("/{id}/documents/{documentId}/segments/{segmentId}")
    public ApiResponse<Void> deleteSegment(@PathVariable String id,
                                           @PathVariable String documentId,
                                           @PathVariable String segmentId) {
        knowledgeService.deleteSegment(id, segmentId);
        return ApiResponse.ok();
    }

    /** Flip a chunk's enabled flag: disabled chunks are removed from the vector store. */
    @PutMapping("/{id}/documents/{documentId}/segments/{segmentId}/enabled")
    public ApiResponse<SegmentEntity> setSegmentEnabled(@PathVariable String id,
                                                        @PathVariable String documentId,
                                                        @PathVariable String segmentId,
                                                        @RequestBody EnabledRequest req) {
        SegmentEntity updated = knowledgeService.setSegmentEnabled(id, segmentId,
                Boolean.TRUE.equals(req.enabled()));
        if (updated == null) {
            return ApiResponse.error("segment_not_found", "Segment not found: " + segmentId);
        }
        return ApiResponse.ok(updated);
    }

    // --------------------------------------------------------------- retrieval

    /** Recent hit-test queries against this dataset — for debugging retrieval quality. */
    @GetMapping("/{id}/hit-testing/history")
    public ApiResponse<List<HitTestingLogEntity>> hitTestingHistory(@PathVariable String id,
                                                                    @RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.ok(knowledgeService.listHitTestingHistory(id, limit));
    }

    /** Hit testing (a.k.a. "try retrieval"): dry-run the retriever for a query. */
    @PostMapping("/{id}/retrieve")
    public ApiResponse<List<RetrievedSegment>> retrieve(@PathVariable String id,
                                                        @RequestBody RetrieveRequestDto req) {
        RetrievalRequest r = RetrievalRequest.builder()
                .query(req.getQuery())
                .method(req.getMethod())
                .topK(req.getTopK())
                .scoreThreshold(req.getScoreThreshold())
                .vectorWeight(req.getVectorWeight())
                .metadataFilter(req.getMetadataFilter())
                .build();
        return ApiResponse.ok(knowledgeService.retrieve(id, r));
    }

    /** Body for the two text-ingestion endpoints. */
    public record AddTextRequest(String name, String text) {}

    /** Body for segment content edit. */
    public record UpdateSegmentRequest(String content) {}

    /** Body for the enabled toggle endpoint. */
    public record EnabledRequest(Boolean enabled) {}
}
