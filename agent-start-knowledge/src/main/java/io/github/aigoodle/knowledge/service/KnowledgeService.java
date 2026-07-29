package io.github.aigoodle.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.knowledge.async.DocumentIngestQueueEntity;
import io.github.aigoodle.knowledge.async.DocumentIngestionQueue;
import io.github.aigoodle.knowledge.async.DocumentIngestionTask;
import io.github.aigoodle.knowledge.chunk.Chunk;
import io.github.aigoodle.knowledge.chunk.ChunkerRegistry;
import io.github.aigoodle.knowledge.chunk.TextCleaner;
import io.github.aigoodle.knowledge.config.ProcessRule;
import io.github.aigoodle.knowledge.entity.DatasetEntity;
import io.github.aigoodle.knowledge.entity.HitTestingLogEntity;
import io.github.aigoodle.knowledge.entity.KnowledgeDocumentEntity;
import io.github.aigoodle.knowledge.entity.SegmentEntity;
import io.github.aigoodle.knowledge.enums.DocumentStatus;
import io.github.aigoodle.knowledge.index.IndexingService;
import io.github.aigoodle.knowledge.mapper.DocumentIngestQueueMapper;
import io.github.aigoodle.knowledge.mapper.HitTestingLogMapper;
import io.github.aigoodle.knowledge.mapper.KnowledgeDocumentMapper;
import io.github.aigoodle.knowledge.reader.DocumentExtractor;
import io.github.aigoodle.knowledge.retrieve.HybridRetriever;
import io.github.aigoodle.knowledge.retrieve.RetrievalRequest;
import io.github.aigoodle.knowledge.retrieve.RetrievedSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The primary entry point of the knowledge module: ingest documents into a dataset
 * (extract → clean → chunk → index) and retrieve relevant chunks for a query.
 */
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private final DatasetService datasetService;
    private final KnowledgeDocumentMapper documentMapper;
    private final ChunkerRegistry chunkerRegistry;
    private final IndexingService indexingService;
    private final HybridRetriever retriever;
    private final DocumentExtractor extractor;
    /** Optional: when a HitTestingLogMapper is on the classpath, we record every hit. */
    private HitTestingLogMapper hitTestingLogMapper;
    /**
     * Optional async ingestion queue. When set, {@code addText/Markdown/File}
     * only extract synchronously (fast) then hand off the chunking + indexing
     * step to the queue — upload responses return in ms instead of blocking
     * on embedding-model round-trips. When null the pipeline stays fully
     * synchronous (original behaviour).
     */
    private DocumentIngestionQueue ingestionQueue;
    private DocumentIngestQueueMapper ingestQueueMapper;

    public KnowledgeService(DatasetService datasetService, KnowledgeDocumentMapper documentMapper,
                            ChunkerRegistry chunkerRegistry, IndexingService indexingService,
                            HybridRetriever retriever, DocumentExtractor extractor) {
        this.datasetService = datasetService;
        this.documentMapper = documentMapper;
        this.chunkerRegistry = chunkerRegistry;
        this.indexingService = indexingService;
        this.retriever = retriever;
        this.extractor = extractor;
    }

    /**
     * Wire the async ingestion queue. Auto-config injects this when the host
     * turns on {@code spring-agent.knowledge.async.enabled=true} (with either
     * a RabbitMQ broker or the built-in in-memory executor fallback).
     */
    public void setIngestionQueue(DocumentIngestionQueue queue, DocumentIngestQueueMapper mapper) {
        this.ingestionQueue = queue;
        this.ingestQueueMapper = mapper;
    }

    public void setHitTestingLogMapper(HitTestingLogMapper hitTestingLogMapper) {
        this.hitTestingLogMapper = hitTestingLogMapper;
    }

    // ----------------------------------------------------------- ingestion

    public KnowledgeDocumentEntity addText(String datasetId, String name, String text) {
        return submit(datasetId, name, "text", extractor.extractText(text));
    }

    public KnowledgeDocumentEntity addMarkdown(String datasetId, String name, String markdown) {
        return submit(datasetId, name, "markdown", extractor.extractMarkdown(markdown, name));
    }

    public KnowledgeDocumentEntity addFile(String datasetId, String filename, byte[] bytes) {
        return submit(datasetId, filename, "file", extractor.extractFile(bytes, filename));
    }

    /**
     * Common entry: whether the pipeline runs sync or async is a single
     * {@link #ingestionQueue} check. Sync path is the historical
     * {@link #ingest} — inline extract → clean → chunk → index. Async path
     * inserts a {@code PENDING} document row + a sidecar queue row (carrying
     * the raw text), publishes a task, and returns the document immediately.
     */
    private KnowledgeDocumentEntity submit(String datasetId, String name, String sourceType, String extractedText) {
        if (ingestionQueue != null && ingestQueueMapper != null) {
            return enqueueIngest(datasetId, name, sourceType, extractedText);
        }
        return ingest(datasetId, name, sourceType, extractedText);
    }

    /**
     * Async submission: persist just enough state to run the pipeline later
     * (document row in {@code PENDING} status + sidecar row with the raw
     * text) and hand the task to the queue. Returns immediately so the HTTP
     * upload endpoint doesn't block on chunking + embedding.
     */
    private KnowledgeDocumentEntity enqueueIngest(String datasetId, String name, String sourceType,
                                                  String extractedText) {
        DatasetEntity dataset = datasetService.require(datasetId);

        KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
        doc.setTenantId(dataset.getTenantId());
        doc.setDatasetId(datasetId);
        doc.setName(name);
        doc.setSourceType(sourceType);
        doc.setStatus(DocumentStatus.PENDING);
        doc.setEnabled(Boolean.TRUE);
        doc.setWordCount(extractedText == null ? 0 : extractedText.length());
        documentMapper.insert(doc);

        DocumentIngestQueueEntity task = new DocumentIngestQueueEntity();
        task.setDocumentId(doc.getId());
        task.setDatasetId(datasetId);
        task.setTenantId(dataset.getTenantId());
        task.setFilename(name);
        task.setSourceType(sourceType);
        task.setRawText(extractedText);
        task.setRetryCount(0);
        LocalDateTime now = LocalDateTime.now();
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        ingestQueueMapper.insert(task);

        ingestionQueue.enqueue(new DocumentIngestionTask(doc.getId(), 0));
        log.info("Queued async ingestion for document '{}' (id={}) into dataset {}",
                name, doc.getId(), datasetId);
        return doc;
    }

    /**
     * Result payload for {@link #previewChunks(byte[], String, ProcessRule, int)}.
     * {@code totalChunks} is the full count the chunker produced; {@code chunks}
     * only carries the first {@code limit} entries so the wire payload stays
     * small on huge documents.
     */
    public record ChunkPreview(int totalChunks, List<ChunkView> chunks) {
        public record ChunkView(int index, String text, int tokens) {}
    }

    /**
     * Chunk a file <b>without persisting anything</b>. Runs the exact same
     * extract → clean → chunk pipeline as {@link #ingest} so the preview the
     * user sees on the "文本分段与清洗" wizard step matches what the dataset
     * actually gets on save. No document row, no vector store call, no LLM
     * cost — just the chunker output truncated to {@code limit} entries.
     *
     * @param bytes    raw uploaded file bytes
     * @param filename original filename (used by the reader registry to pick a
     *                 reader by extension: pdf → TikaDocumentReader, md →
     *                 MarkdownDocumentReader, etc.)
     * @param rule     the {@link ProcessRule} the user is currently editing —
     *                 preview reflects THIS rule, not the dataset's persisted
     *                 rule. Falls back to {@code naive()} defaults when null.
     * @param limit    max chunks to return (default 10). {@code totalChunks} on
     *                 the response is always the full count for the truncation
     *                 hint on the UI.
     */
    public ChunkPreview previewChunks(byte[] bytes, String filename, ProcessRule rule, int limit) {
        ProcessRule effective = rule == null ? ProcessRule.naive() : rule;
        int cap = limit <= 0 ? 10 : Math.min(limit, 50);
        String extracted = extractor.extractFile(bytes, filename);
        String cleaned = TextCleaner.clean(extracted, effective);

        Map<String, Object> baseMetadata = new HashMap<>();
        baseMetadata.put("documentName", filename);
        baseMetadata.put("source", "preview");
        List<Chunk> chunks = chunkerRegistry.get(effective.getTemplate()).chunk(cleaned, effective, baseMetadata);

        List<ChunkPreview.ChunkView> view = new ArrayList<>(Math.min(chunks.size(), cap));
        for (int i = 0; i < chunks.size() && i < cap; i++) {
            Chunk c = chunks.get(i);
            String text = c.getContent() == null ? "" : c.getContent();
            // Use the same TokenCounter the persisted chunks use — otherwise
            // the preview badge diverges from the real segment tokens table.
            view.add(new ChunkPreview.ChunkView(i + 1, text, c.tokenCount()));
        }
        return new ChunkPreview(chunks.size(), view);
    }

    /** Full ingestion pipeline with status tracking and graceful failure recording. */
    public KnowledgeDocumentEntity ingest(String datasetId, String name, String sourceType, String extractedText) {
        DatasetEntity dataset = datasetService.require(datasetId);

        KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
        doc.setTenantId(dataset.getTenantId());
        doc.setDatasetId(datasetId);
        doc.setName(name);
        doc.setSourceType(sourceType);
        doc.setStatus(DocumentStatus.PARSING);
        doc.setEnabled(Boolean.TRUE);
        doc.setWordCount(extractedText == null ? 0 : extractedText.length());
        documentMapper.insert(doc);

        try {
            ProcessRule rule = datasetService.processRule(dataset);
            String cleaned = TextCleaner.clean(extractedText, rule);

            doc.setStatus(DocumentStatus.CHUNKING);
            documentMapper.updateById(doc);

            Map<String, Object> baseMetadata = new HashMap<>();
            baseMetadata.put("documentName", name);
            baseMetadata.put("source", sourceType);
            List<Chunk> chunks = chunkerRegistry.get(rule.getTemplate()).chunk(cleaned, rule, baseMetadata);

            doc.setStatus(DocumentStatus.INDEXING);
            documentMapper.updateById(doc);

            int count = indexingService.index(dataset, doc, chunks);

            doc.setSegmentCount(count);
            doc.setStatus(DocumentStatus.COMPLETED);
            documentMapper.updateById(doc);

            datasetService.updateCounts(dataset, 1, count);
            log.info("Ingested document '{}' into dataset {} as {} segments", name, datasetId, count);
            return doc;
        } catch (Exception e) {
            log.error("Failed to ingest document '{}' into dataset {}: {}", name, datasetId, e.getMessage(), e);
            doc.setStatus(DocumentStatus.FAILED);
            doc.setErrorMessage(e.getMessage());
            documentMapper.updateById(doc);
            return doc;
        }
    }

    public void deleteDocument(String documentId) {
        KnowledgeDocumentEntity doc = documentMapper.selectById(documentId);
        if (doc == null) {
            return;
        }
        DatasetEntity dataset = datasetService.require(doc.getDatasetId());
        indexingService.removeDocument(dataset, documentId);
        documentMapper.deleteById(documentId);
        datasetService.updateCounts(dataset, -1, -nz(doc.getSegmentCount()));
    }

    public List<KnowledgeDocumentEntity> listDocuments(String datasetId) {
        return documentMapper.selectList(new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                .eq(KnowledgeDocumentEntity::getDatasetId, datasetId));
    }

    public KnowledgeDocumentEntity getDocument(String documentId) {
        return documentMapper.selectById(documentId);
    }

    /** Read paginated segments for a single document (for the "chunks" tab of the UI). */
    public List<SegmentEntity> listSegments(String documentId, int page, int pageSize) {
        int p = Math.max(1, page);
        int size = Math.min(200, Math.max(1, pageSize));
        return indexingService.listSegments(documentId, p, size);
    }

    /** Edit a segment's content: re-embeds and swaps the vector in the dataset store. */
    public SegmentEntity updateSegment(String datasetId, String segmentId, String newContent) {
        DatasetEntity dataset = datasetService.require(datasetId);
        return indexingService.updateSegment(dataset, segmentId, newContent);
    }

    public void deleteSegment(String datasetId, String segmentId) {
        DatasetEntity dataset = datasetService.require(datasetId);
        indexingService.deleteSegment(dataset, segmentId);
    }

    /** Toggle a segment's enabled flag; syncs the vector store accordingly. */
    public SegmentEntity setSegmentEnabled(String datasetId, String segmentId, boolean enabled) {
        DatasetEntity dataset = datasetService.require(datasetId);
        return indexingService.setSegmentEnabled(dataset, segmentId, enabled);
    }

    /** Re-embed every enabled segment of a document — after swapping embedding models. */
    public int reindexDocument(String datasetId, String documentId) {
        DatasetEntity dataset = datasetService.require(datasetId);
        return indexingService.reembedDocument(dataset, documentId);
    }

    /** Append a manually-authored chunk to a document. */
    public SegmentEntity appendSegment(String datasetId, String documentId, String content) {
        DatasetEntity dataset = datasetService.require(datasetId);
        KnowledgeDocumentEntity doc = documentMapper.selectById(documentId);
        if (doc == null) {
            return null;
        }
        SegmentEntity seg = indexingService.appendSegment(dataset, doc, content);
        // Keep the document's segment counter in sync so the UI badge stays accurate.
        doc.setSegmentCount((doc.getSegmentCount() == null ? 0 : doc.getSegmentCount()) + 1);
        documentMapper.updateById(doc);
        datasetService.updateCounts(dataset, 0, 1);
        return seg;
    }

    // ------------------------------------------------------------ retrieval

    public List<RetrievedSegment> retrieve(String datasetId, RetrievalRequest request) {
        DatasetEntity dataset = datasetService.require(datasetId);
        long start = System.currentTimeMillis();
        List<RetrievedSegment> hits = retriever.retrieve(dataset, datasetService.retrievalConfig(dataset), request);
        recordHitTest(dataset, request, hits, (int) (System.currentTimeMillis() - start));
        return hits;
    }

    private void recordHitTest(DatasetEntity dataset, RetrievalRequest request,
                                List<RetrievedSegment> hits, int latencyMs) {
        if (hitTestingLogMapper == null) return;
        try {
            HitTestingLogEntity entity = new HitTestingLogEntity();
            entity.setTenantId(dataset.getTenantId());
            entity.setDatasetId(dataset.getId());
            entity.setQuery(request.getQuery());
            entity.setMethod(request.getMethod() == null ? null : String.valueOf(request.getMethod()));
            entity.setTopK(request.getTopK());
            entity.setHitCount(hits == null ? 0 : hits.size());
            entity.setLatencyMs(latencyMs);
            // Truncate result payload — the JSON per row can be huge for topK 20+ with long chunks.
            List<Map<String, Object>> preview = new ArrayList<>();
            if (hits != null) {
                for (RetrievedSegment h : hits) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("segmentId", h.getSegmentId());
                    m.put("documentId", h.getDocumentId());
                    m.put("position", h.getPosition());
                    m.put("score", h.getScore());
                    String c = h.getContent();
                    m.put("preview", c == null ? null : c.substring(0, Math.min(200, c.length())));
                    preview.add(m);
                }
            }
            entity.setResultsJson(JsonUtils.toJson(preview));
            hitTestingLogMapper.insert(entity);
        } catch (Exception e) {
            log.debug("Failed to record hit-test log: {}", e.getMessage());
        }
    }

    /** Read recent hit-test history for a dataset (newest first). */
    public List<HitTestingLogEntity> listHitTestingHistory(String datasetId, int limit) {
        if (hitTestingLogMapper == null) return List.of();
        int size = Math.min(500, Math.max(1, limit));
        return hitTestingLogMapper.selectList(new LambdaQueryWrapper<HitTestingLogEntity>()
                .eq(HitTestingLogEntity::getDatasetId, datasetId)
                .orderByDesc(HitTestingLogEntity::getCreatedAt)
                .last("limit " + size));
    }

    public List<RetrievedSegment> retrieve(String datasetId, String query) {
        return retrieve(datasetId, RetrievalRequest.builder().query(query).build());
    }

    /** Retrieve across several datasets and merge by fused score. */
    public List<RetrievedSegment> retrieve(List<String> datasetIds, RetrievalRequest request) {
        int topK = request.getTopK() != null ? request.getTopK() : 5;
        return datasetIds.stream()
                .flatMap(id -> retrieve(id, request).stream())
                .sorted(Comparator.comparingDouble(RetrievedSegment::getScore).reversed())
                .limit(topK)
                .toList();
    }

    private static int nz(Integer i) {
        return i == null ? 0 : i;
    }
}
