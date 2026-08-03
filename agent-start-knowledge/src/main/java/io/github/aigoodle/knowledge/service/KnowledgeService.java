package io.github.aigoodle.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.knowledge.async.DocumentIngestionQueue;
import io.github.aigoodle.knowledge.config.ProcessRule;
import io.github.aigoodle.knowledge.entity.DatasetEntity;
import io.github.aigoodle.knowledge.entity.HitTestingLogEntity;
import io.github.aigoodle.knowledge.entity.KnowledgeDocumentEntity;
import io.github.aigoodle.knowledge.entity.SegmentEntity;
import io.github.aigoodle.knowledge.index.IndexingService;
import io.github.aigoodle.knowledge.mapper.DocumentIngestQueueMapper;
import io.github.aigoodle.knowledge.mapper.HitTestingLogMapper;
import io.github.aigoodle.knowledge.mapper.KnowledgeDocumentMapper;
import io.github.aigoodle.knowledge.reader.DocumentExtractor;
import io.github.aigoodle.knowledge.reader.model.ParsedDocument;
import io.github.aigoodle.knowledge.retrieve.HybridRetriever;
import io.github.aigoodle.knowledge.retrieve.RetrievalRequest;
import io.github.aigoodle.knowledge.retrieve.RetrievedSegment;
import io.github.aigoodle.knowledge.chunk.ChunkerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Base64;

/**
 * Public facade for document ingestion, segment management and knowledge retrieval.
 * The ingestion pipeline itself lives in {@link DocumentIngestionService}; this class
 * keeps the starter's established API while presenting each operation at domain level.
 */
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private final DatasetService datasetService;
    private final KnowledgeDocumentMapper documentMapper;
    private final IndexingService indexingService;
    private final HybridRetriever retriever;
    private final DocumentIngestionService ingestionService;

    private HitTestingLogMapper hitTestingLogMapper;

    public KnowledgeService(DatasetService datasetService,
                            KnowledgeDocumentMapper documentMapper,
                            ChunkerRegistry chunkerRegistry,
                            IndexingService indexingService,
                            HybridRetriever retriever,
                            DocumentExtractor extractor) {
        this.datasetService = datasetService;
        this.documentMapper = documentMapper;
        this.indexingService = indexingService;
        this.retriever = retriever;
        this.ingestionService = new DocumentIngestionService(datasetService, documentMapper,
                chunkerRegistry, indexingService, extractor);
    }

    public void setIngestionQueue(DocumentIngestionQueue queue,
                                  DocumentIngestQueueMapper queueMapper) {
        ingestionService.configureQueue(queue, queueMapper);
    }

    public void setHitTestingLogMapper(HitTestingLogMapper hitTestingLogMapper) {
        this.hitTestingLogMapper = hitTestingLogMapper;
    }

    public KnowledgeDocumentEntity addText(String datasetId, String name, String text) {
        return ingestionService.addText(datasetId, name, text);
    }

    public KnowledgeDocumentEntity addMarkdown(String datasetId, String name, String markdown) {
        return ingestionService.addMarkdown(datasetId, name, markdown);
    }

    public KnowledgeDocumentEntity addFile(String datasetId, String filename, byte[] bytes) {
        return ingestionService.addFile(datasetId, filename, bytes);
    }

    public record ChunkPreview(String parser, String mediaType, int pageCount, int blockCount,
                               List<String> warnings, int totalChunks, List<ChunkView> chunks) {

        public record ChunkView(int index, String text, int tokens, Map<String, Object> metadata) {
        }
    }

    public ChunkPreview previewChunks(byte[] bytes, String filename,
                                      ProcessRule processRule, int limit) {
        return ingestionService.preview(bytes, filename, processRule, limit);
    }

    public KnowledgeDocumentEntity ingest(String datasetId, String name,
                                          String sourceType, String extractedText) {
        return ingestionService.ingest(datasetId, name, sourceType, extractedText);
    }

    public void deleteDocument(String documentId) {
        KnowledgeDocumentEntity document = documentMapper.selectById(documentId);
        if (document == null) {
            return;
        }
        DatasetEntity dataset = datasetService.require(document.getDatasetId());
        indexingService.removeDocument(dataset, documentId);
        documentMapper.deleteById(documentId);
        datasetService.applyCountChange(dataset,
                DatasetCountChange.documentRemoved(valueOrZero(document.getSegmentCount())));
    }

    public List<KnowledgeDocumentEntity> listDocuments(String datasetId) {
        return documentMapper.selectList(new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                .eq(KnowledgeDocumentEntity::getDatasetId, datasetId));
    }

    public KnowledgeDocumentEntity getDocument(String documentId) {
        return documentMapper.selectById(documentId);
    }

    public ParsedDocument getParsedDocument(String documentId) {
        return JsonUtils.parse(documentMapper.selectParsedDocumentJson(documentId), ParsedDocument.class);
    }

    public KnowledgeDocumentEntity reparseDocument(String datasetId, String documentId) {
        String encoded = documentMapper.selectSourceDataBase64(documentId);
        if (encoded == null || encoded.isBlank()) return null;
        return ingestionService.reparse(datasetId, documentId, Base64.getDecoder().decode(encoded));
    }

    public List<SegmentEntity> listSegments(String documentId, int page, int pageSize) {
        int pageNumber = Math.max(1, page);
        int boundedPageSize = Math.min(200, Math.max(1, pageSize));
        return indexingService.listSegments(documentId, pageNumber, boundedPageSize);
    }

    public SegmentEntity updateSegment(String datasetId, String segmentId, String newContent) {
        DatasetEntity dataset = datasetService.require(datasetId);
        return indexingService.updateSegment(dataset, segmentId, newContent);
    }

    public void deleteSegment(String datasetId, String segmentId) {
        DatasetEntity dataset = datasetService.require(datasetId);
        indexingService.deleteSegment(dataset, segmentId);
    }

    public SegmentEntity setSegmentEnabled(String datasetId, String segmentId, boolean enabled) {
        DatasetEntity dataset = datasetService.require(datasetId);
        return indexingService.setSegmentEnabled(dataset, segmentId, enabled);
    }

    public int reindexDocument(String datasetId, String documentId) {
        DatasetEntity dataset = datasetService.require(datasetId);
        return indexingService.reembedDocument(dataset, documentId);
    }

    public SegmentEntity appendSegment(String datasetId, String documentId, String content) {
        DatasetEntity dataset = datasetService.require(datasetId);
        KnowledgeDocumentEntity document = documentMapper.selectById(documentId);
        if (document == null) {
            return null;
        }

        SegmentEntity segment = indexingService.appendSegment(dataset, document, content);
        document.setSegmentCount(valueOrZero(document.getSegmentCount()) + 1);
        documentMapper.updateById(document);
        datasetService.applyCountChange(dataset, DatasetCountChange.segmentAdded());
        return segment;
    }

    public List<RetrievedSegment> retrieve(String datasetId, RetrievalRequest request) {
        DatasetEntity dataset = datasetService.require(datasetId);
        long startedAt = System.currentTimeMillis();
        List<RetrievedSegment> retrievedSegments = retriever.retrieve(
                dataset, datasetService.retrievalConfig(dataset), request);
        recordHitTest(dataset, request, retrievedSegments,
                (int) (System.currentTimeMillis() - startedAt));
        return retrievedSegments;
    }

    public List<RetrievedSegment> retrieve(String datasetId, String query) {
        return retrieve(datasetId, RetrievalRequest.builder().query(query).build());
    }

    public List<RetrievedSegment> retrieve(List<String> datasetIds, RetrievalRequest request) {
        int resultLimit = request.getTopK() != null ? request.getTopK() : 5;
        return datasetIds.stream()
                .flatMap(datasetId -> retrieve(datasetId, request).stream())
                .sorted(Comparator.comparingDouble(RetrievedSegment::getScore).reversed())
                .limit(resultLimit)
                .toList();
    }

    public List<HitTestingLogEntity> listHitTestingHistory(String datasetId, int limit) {
        if (hitTestingLogMapper == null) {
            return List.of();
        }
        int boundedLimit = Math.min(500, Math.max(1, limit));
        return hitTestingLogMapper.selectList(new LambdaQueryWrapper<HitTestingLogEntity>()
                .eq(HitTestingLogEntity::getDatasetId, datasetId)
                .orderByDesc(HitTestingLogEntity::getCreatedAt)
                .last("limit " + boundedLimit));
    }

    private void recordHitTest(DatasetEntity dataset, RetrievalRequest request,
                               List<RetrievedSegment> retrievedSegments, int latencyMillis) {
        if (hitTestingLogMapper == null) {
            return;
        }
        try {
            HitTestingLogEntity logEntry = new HitTestingLogEntity();
            logEntry.setTenantId(dataset.getTenantId());
            logEntry.setDatasetId(dataset.getId());
            logEntry.setQuery(request.getQuery());
            logEntry.setMethod(request.getMethod() == null
                    ? null : String.valueOf(request.getMethod()));
            logEntry.setTopK(request.getTopK());
            logEntry.setHitCount(retrievedSegments == null ? 0 : retrievedSegments.size());
            logEntry.setLatencyMs(latencyMillis);
            logEntry.setResultsJson(JsonUtils.toJson(createResultPreview(retrievedSegments)));
            hitTestingLogMapper.insert(logEntry);
        } catch (Exception exception) {
            log.debug("Failed to record hit-test log: {}", exception.getMessage());
        }
    }

    private static List<Map<String, Object>> createResultPreview(
            List<RetrievedSegment> retrievedSegments) {
        if (retrievedSegments == null || retrievedSegments.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> preview = new ArrayList<>(retrievedSegments.size());
        for (RetrievedSegment segment : retrievedSegments) {
            Map<String, Object> item = new HashMap<>();
            item.put("segmentId", segment.getSegmentId());
            item.put("documentId", segment.getDocumentId());
            item.put("position", segment.getPosition());
            item.put("score", segment.getScore());
            String content = segment.getContent();
            item.put("preview", content == null
                    ? null : content.substring(0, Math.min(200, content.length())));
            preview.add(item);
        }
        return preview;
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
