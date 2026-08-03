package io.github.aigoodle.knowledge.async;

import io.github.aigoodle.knowledge.chunk.Chunk;
import io.github.aigoodle.knowledge.chunk.ChunkerRegistry;
import io.github.aigoodle.knowledge.chunk.TextCleaner;
import io.github.aigoodle.knowledge.chunk.StructuredDocumentChunker;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.knowledge.config.ProcessRule;
import io.github.aigoodle.knowledge.entity.DatasetEntity;
import io.github.aigoodle.knowledge.entity.KnowledgeDocumentEntity;
import io.github.aigoodle.knowledge.enums.DocumentStatus;
import io.github.aigoodle.knowledge.index.IndexingService;
import io.github.aigoodle.knowledge.mapper.DocumentIngestQueueMapper;
import io.github.aigoodle.knowledge.mapper.KnowledgeDocumentMapper;
import io.github.aigoodle.knowledge.service.DatasetCountChange;
import io.github.aigoodle.knowledge.service.DatasetService;
import io.github.aigoodle.knowledge.reader.model.ParsedDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The actual "extract → clean → chunk → index" work — the same pipeline
 * {@code KnowledgeService.ingest()} runs, but split out so it can be executed
 * either inline (sync fallback) or off a queue consumer (async).
 *
 * <p>Loads context from the DB (dataset + processRule + the sidecar
 * {@link DocumentIngestQueueEntity}), runs the pipeline, updates document
 * status as it progresses, then deletes the sidecar row on success. A crash
 * mid-way leaves the sidecar row in place so a re-run picks up cleanly.</p>
 */
public class DocumentIngestionRunner {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionRunner.class);

    private final DatasetService datasetService;
    private final KnowledgeDocumentMapper documentMapper;
    private final DocumentIngestQueueMapper queueMapper;
    private final ChunkerRegistry chunkerRegistry;
    private final IndexingService indexingService;
    private final StructuredDocumentChunker structuredChunker;

    public DocumentIngestionRunner(DatasetService datasetService,
                                   KnowledgeDocumentMapper documentMapper,
                                   DocumentIngestQueueMapper queueMapper,
                                   ChunkerRegistry chunkerRegistry,
                                   IndexingService indexingService) {
        this.datasetService = datasetService;
        this.documentMapper = documentMapper;
        this.queueMapper = queueMapper;
        this.chunkerRegistry = chunkerRegistry;
        this.indexingService = indexingService;
        this.structuredChunker = new StructuredDocumentChunker(chunkerRegistry);
    }

    /**
     * Load the sidecar row + document + dataset, run the chunk/index pipeline,
     * update the document row's status as we go. Deletes the sidecar row on
     * success; leaves it in place on failure so the caller (queue impl) can
     * decide to retry or DLQ.
     *
     * @return {@code true} if the document reached {@code COMPLETED};
     *         {@code false} on failure. Never throws — the queue layer needs
     *         a clean boolean to make its retry/DLQ decision.
     */
    public boolean run(String documentId) {
        DocumentIngestQueueEntity task = queueMapper.selectById(documentId);
        if (task == null) {
            log.warn("Ingest task {} not found in queue table — probably already processed", documentId);
            return true;
        }
        KnowledgeDocumentEntity doc = documentMapper.selectById(documentId);
        if (doc == null) {
            log.warn("Document {} referenced by ingest task no longer exists; dropping task", documentId);
            queueMapper.deleteById(documentId);
            return true;
        }
        DatasetEntity dataset;
        try {
            dataset = datasetService.require(doc.getDatasetId());
        } catch (Exception e) {
            log.error("Dataset {} for document {} not found; marking FAILED", doc.getDatasetId(), documentId);
            markFailed(doc, "dataset not found: " + e.getMessage());
            queueMapper.deleteById(documentId);
            return false;
        }

        try {
            ProcessRule rule = datasetService.processRule(dataset);

            doc.setStatus(DocumentStatus.CHUNKING);
            documentMapper.updateById(doc);

            Map<String, Object> baseMetadata = new HashMap<>();
            baseMetadata.put("documentName", doc.getName());
            baseMetadata.put("source", task.getSourceType());
            ParsedDocument parsed = task.getParsedDocumentJson() == null
                    ? null : JsonUtils.parse(task.getParsedDocumentJson(), ParsedDocument.class);
            List<Chunk> chunks = parsed == null
                    ? chunkerRegistry.get(rule.getTemplate()).chunk(TextCleaner.clean(task.getRawText(), rule), rule, baseMetadata)
                    : structuredChunker.chunk(parsed, rule, baseMetadata);

            doc.setStatus(DocumentStatus.INDEXING);
            documentMapper.updateById(doc);

            int count = indexingService.index(dataset, doc, chunks);

            doc.setSegmentCount(count);
            doc.setStatus(DocumentStatus.COMPLETED);
            documentMapper.updateById(doc);

            datasetService.applyCountChange(
                    dataset, DatasetCountChange.documentAdded(count));
            queueMapper.deleteById(documentId);
            log.info("Async-ingested document '{}' into dataset {} as {} segments",
                    doc.getName(), doc.getDatasetId(), count);
            return true;
        } catch (Exception e) {
            log.error("Failed to async-ingest document '{}' into dataset {}: {}",
                    doc.getName(), doc.getDatasetId(), e.getMessage(), e);
            markFailed(doc, e.getMessage());
            return false;
        }
    }

    private void markFailed(KnowledgeDocumentEntity doc, String msg) {
        doc.setStatus(DocumentStatus.FAILED);
        doc.setErrorMessage(msg);
        documentMapper.updateById(doc);
    }
}
