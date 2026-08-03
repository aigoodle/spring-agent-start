package io.github.aigoodle.web.controller;

import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.knowledge.config.ProcessRule;
import io.github.aigoodle.knowledge.entity.KnowledgeDocumentEntity;
import io.github.aigoodle.knowledge.service.KnowledgeService;
import io.github.aigoodle.knowledge.reader.model.ParsedDocument;
import io.github.aigoodle.web.common.ApiResponse;
import io.github.aigoodle.web.support.UploadedDocument;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** Document ingestion, inspection and reindexing endpoints for datasets. */
@RestController
@ConditionalOnBean(KnowledgeService.class)
@RequestMapping("${spring-agent.web.base-path:}/datasets")
public class DatasetDocumentController {

    private final KnowledgeService knowledgeService;

    public DatasetDocumentController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @GetMapping("/{id}/documents")
    public ApiResponse<List<KnowledgeDocumentEntity>> listDocuments(@PathVariable String id) {
        return ApiResponse.ok(knowledgeService.listDocuments(id));
    }

    @GetMapping("/{id}/documents/{documentId}")
    public ApiResponse<KnowledgeDocumentEntity> getDocument(
            @PathVariable String id, @PathVariable String documentId) {
        KnowledgeDocumentEntity document = knowledgeService.getDocument(documentId);
        return document == null
                ? ApiResponse.error("document_not_found", "Document not found: " + documentId)
                : ApiResponse.ok(document);
    }

    @GetMapping("/{id}/documents/{documentId}/parsed")
    public ApiResponse<ParsedDocument> getParsedDocument(
            @PathVariable String id, @PathVariable String documentId) {
        ParsedDocument parsed = knowledgeService.getParsedDocument(documentId);
        return parsed == null
                ? ApiResponse.error("parsed_document_not_found", "Parsed document not found: " + documentId)
                : ApiResponse.ok(parsed);
    }

    @PostMapping("/{id}/documents/text")
    public ApiResponse<KnowledgeDocumentEntity> addText(
            @PathVariable String id, @RequestBody AddTextRequest request) {
        return ApiResponse.ok(knowledgeService.addText(id, request.name(), request.text()));
    }

    @PostMapping("/{id}/documents/markdown")
    public ApiResponse<KnowledgeDocumentEntity> addMarkdown(
            @PathVariable String id, @RequestBody AddTextRequest request) {
        return ApiResponse.ok(knowledgeService.addMarkdown(id, request.name(), request.text()));
    }

    @PostMapping("/{id}/documents/upload")
    public ApiResponse<KnowledgeDocumentEntity> upload(
            @PathVariable String id, @RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ApiResponse.error("file_required", "An uploaded file is required");
        }
        UploadedDocument upload = UploadedDocument.from(file);
        return ApiResponse.ok(knowledgeService.addFile(id, upload.filename(), upload.content()));
    }

    @DeleteMapping("/{id}/documents/{documentId}")
    public ApiResponse<Void> deleteDocument(@PathVariable String id,
                                            @PathVariable String documentId) {
        knowledgeService.deleteDocument(documentId);
        return ApiResponse.ok();
    }

    @PostMapping(value = "/preview-chunks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<KnowledgeService.ChunkPreview> previewChunks(
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "rule", required = false) String ruleJson,
            @RequestParam(value = "limit", defaultValue = "10") int limit) {
        if (file == null || file.isEmpty()) {
            return ApiResponse.error("file_required", "An uploaded file is required");
        }
        ProcessRule processRule;
        try {
            processRule = parseProcessRule(ruleJson);
        } catch (Exception exception) {
            return ApiResponse.error(
                    "rule_invalid", "'rule' is not valid ProcessRule JSON: " + exception.getMessage());
        }
        UploadedDocument upload = UploadedDocument.from(file);
        return ApiResponse.ok(knowledgeService.previewChunks(
                upload.content(), upload.filename(), processRule, limit));
    }

    @PostMapping("/{id}/documents/{documentId}/reindex")
    public ApiResponse<Map<String, Object>> reindex(
            @PathVariable String id, @PathVariable String documentId) {
        int segmentCount = knowledgeService.reindexDocument(id, documentId);
        return ApiResponse.ok(Map.of("segmentCount", segmentCount));
    }

    @PostMapping("/{id}/documents/{documentId}/reparse")
    public ApiResponse<KnowledgeDocumentEntity> reparse(
            @PathVariable String id, @PathVariable String documentId) {
        KnowledgeDocumentEntity document = knowledgeService.reparseDocument(id, documentId);
        return document == null
                ? ApiResponse.error("source_not_available", "Original source is not available for reparsing")
                : ApiResponse.ok(document);
    }

    private static ProcessRule parseProcessRule(String ruleJson) {
        return ruleJson == null || ruleJson.isBlank()
                ? ProcessRule.naive()
                : JsonUtils.parse(ruleJson, ProcessRule.class);
    }

    public record AddTextRequest(String name, String text) {
    }
}
