package io.github.aigoodle.knowledge.service;

import io.github.aigoodle.knowledge.async.DocumentIngestQueueEntity;
import io.github.aigoodle.knowledge.async.DocumentIngestionQueue;
import io.github.aigoodle.knowledge.async.DocumentIngestionTask;
import io.github.aigoodle.knowledge.chunk.Chunk;
import io.github.aigoodle.knowledge.chunk.ChunkerRegistry;
import io.github.aigoodle.knowledge.chunk.TextCleaner;
import io.github.aigoodle.knowledge.config.ProcessRule;
import io.github.aigoodle.knowledge.entity.DatasetEntity;
import io.github.aigoodle.knowledge.entity.KnowledgeDocumentEntity;
import io.github.aigoodle.knowledge.enums.DocumentStatus;
import io.github.aigoodle.knowledge.index.IndexingService;
import io.github.aigoodle.knowledge.mapper.DocumentIngestQueueMapper;
import io.github.aigoodle.knowledge.mapper.KnowledgeDocumentMapper;
import io.github.aigoodle.knowledge.reader.DocumentExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Coordinates extraction, chunking, indexing and document status transitions. */
final class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);
    private static final int DEFAULT_PREVIEW_LIMIT = 10;
    private static final int MAX_PREVIEW_LIMIT = 50;

    private final DatasetService datasetService;
    private final KnowledgeDocumentMapper documentMapper;
    private final ChunkerRegistry chunkerRegistry;
    private final IndexingService indexingService;
    private final DocumentExtractor extractor;

    private DocumentIngestionQueue ingestionQueue;
    private DocumentIngestQueueMapper queueMapper;

    DocumentIngestionService(DatasetService datasetService,
                             KnowledgeDocumentMapper documentMapper,
                             ChunkerRegistry chunkerRegistry,
                             IndexingService indexingService,
                             DocumentExtractor extractor) {
        this.datasetService = datasetService;
        this.documentMapper = documentMapper;
        this.chunkerRegistry = chunkerRegistry;
        this.indexingService = indexingService;
        this.extractor = extractor;
    }

    void configureQueue(DocumentIngestionQueue ingestionQueue,
                        DocumentIngestQueueMapper queueMapper) {
        this.ingestionQueue = ingestionQueue;
        this.queueMapper = queueMapper;
    }

    KnowledgeDocumentEntity addText(String datasetId, String name, String text) {
        return submit(datasetId, name, "text", extractor.extractText(text));
    }

    KnowledgeDocumentEntity addMarkdown(String datasetId, String name, String markdown) {
        return submit(datasetId, name, "markdown", extractor.extractMarkdown(markdown, name));
    }

    KnowledgeDocumentEntity addFile(String datasetId, String filename, byte[] bytes) {
        return submit(datasetId, filename, "file", extractor.extractFile(bytes, filename));
    }

    KnowledgeService.ChunkPreview preview(byte[] bytes, String filename,
                                            ProcessRule processRule, int limit) {
        ProcessRule effectiveRule = processRule == null ? ProcessRule.naive() : processRule;
        int previewLimit = limit <= 0
                ? DEFAULT_PREVIEW_LIMIT : Math.min(limit, MAX_PREVIEW_LIMIT);
        String extractedText = extractor.extractFile(bytes, filename);
        String cleanedText = TextCleaner.clean(extractedText, effectiveRule);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentName", filename);
        metadata.put("source", "preview");
        List<Chunk> chunks = chunkerRegistry.get(effectiveRule.getTemplate())
                .chunk(cleanedText, effectiveRule, metadata);

        List<KnowledgeService.ChunkPreview.ChunkView> preview =
                new ArrayList<>(Math.min(chunks.size(), previewLimit));
        for (int index = 0; index < chunks.size() && index < previewLimit; index++) {
            Chunk chunk = chunks.get(index);
            String content = chunk.getContent() == null ? "" : chunk.getContent();
            preview.add(new KnowledgeService.ChunkPreview.ChunkView(
                    index + 1, content, chunk.tokenCount()));
        }
        return new KnowledgeService.ChunkPreview(chunks.size(), preview);
    }

    KnowledgeDocumentEntity ingest(String datasetId, String name,
                                    String sourceType, String extractedText) {
        DatasetEntity dataset = datasetService.require(datasetId);
        KnowledgeDocumentEntity document = createDocument(
                dataset, name, sourceType, extractedText, DocumentStatus.PARSING);

        try {
            ProcessRule processRule = datasetService.processRule(dataset);
            String cleanedText = TextCleaner.clean(extractedText, processRule);
            updateStatus(document, DocumentStatus.CHUNKING);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("documentName", name);
            metadata.put("source", sourceType);
            List<Chunk> chunks = chunkerRegistry.get(processRule.getTemplate())
                    .chunk(cleanedText, processRule, metadata);
            updateStatus(document, DocumentStatus.INDEXING);

            int segmentCount = indexingService.index(dataset, document, chunks);
            document.setSegmentCount(segmentCount);
            updateStatus(document, DocumentStatus.COMPLETED);
            datasetService.applyCountChange(
                    dataset, DatasetCountChange.documentAdded(segmentCount));
            log.info("Ingested document '{}' into dataset {} as {} segments",
                    name, datasetId, segmentCount);
        } catch (Exception exception) {
            log.error("Failed to ingest document '{}' into dataset {}: {}",
                    name, datasetId, exception.getMessage(), exception);
            document.setStatus(DocumentStatus.FAILED);
            document.setErrorMessage(exception.getMessage());
            documentMapper.updateById(document);
        }
        return document;
    }

    private KnowledgeDocumentEntity submit(String datasetId, String name,
                                            String sourceType, String extractedText) {
        if (ingestionQueue != null && queueMapper != null) {
            return enqueue(datasetId, name, sourceType, extractedText);
        }
        return ingest(datasetId, name, sourceType, extractedText);
    }

    private KnowledgeDocumentEntity enqueue(String datasetId, String name,
                                             String sourceType, String extractedText) {
        DatasetEntity dataset = datasetService.require(datasetId);
        KnowledgeDocumentEntity document = createDocument(
                dataset, name, sourceType, extractedText, DocumentStatus.PENDING);

        DocumentIngestQueueEntity queuedDocument = new DocumentIngestQueueEntity();
        queuedDocument.setDocumentId(document.getId());
        queuedDocument.setDatasetId(datasetId);
        queuedDocument.setTenantId(dataset.getTenantId());
        queuedDocument.setFilename(name);
        queuedDocument.setSourceType(sourceType);
        queuedDocument.setRawText(extractedText);
        queuedDocument.setRetryCount(0);
        LocalDateTime now = LocalDateTime.now();
        queuedDocument.setCreatedAt(now);
        queuedDocument.setUpdatedAt(now);
        queueMapper.insert(queuedDocument);

        ingestionQueue.enqueue(new DocumentIngestionTask(document.getId(), 0));
        log.info("Queued async ingestion for document '{}' (id={}) into dataset {}",
                name, document.getId(), datasetId);
        return document;
    }

    private KnowledgeDocumentEntity createDocument(DatasetEntity dataset, String name,
                                                    String sourceType, String extractedText,
                                                    DocumentStatus initialStatus) {
        KnowledgeDocumentEntity document = new KnowledgeDocumentEntity();
        document.setTenantId(dataset.getTenantId());
        document.setDatasetId(dataset.getId());
        document.setName(name);
        document.setSourceType(sourceType);
        document.setStatus(initialStatus);
        document.setEnabled(Boolean.TRUE);
        document.setWordCount(extractedText == null ? 0 : extractedText.length());
        documentMapper.insert(document);
        return document;
    }

    private void updateStatus(KnowledgeDocumentEntity document, DocumentStatus status) {
        document.setStatus(status);
        documentMapper.updateById(document);
    }
}
