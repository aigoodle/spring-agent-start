package io.github.aigoodle.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.common.exception.PlatformException;
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
import io.github.aigoodle.knowledge.nlp.KeywordTokenizer;
import io.github.aigoodle.knowledge.retrieve.HybridRetriever;
import io.github.aigoodle.knowledge.retrieve.RetrievalRequest;
import io.github.aigoodle.knowledge.retrieve.RetrievedSegment;
import io.github.aigoodle.knowledge.chunk.ChunkerRegistry;
import io.github.aigoodle.common.context.UserContextHolder;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Locale;

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

    public DatasetEntity requireDataset(String tenantId, String datasetId) {
        return datasetService.require(tenantId, datasetId);
    }

    public KnowledgeDocumentEntity requireDocument(String datasetId, String documentId) {
        return requireDocument(UserContextHolder.currentTenantId(), datasetId, documentId);
    }

    public KnowledgeDocumentEntity requireDocument(String tenantId, String datasetId, String documentId) {
        KnowledgeDocumentEntity document = documentMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                        .eq(KnowledgeDocumentEntity::getTenantId, tenantId)
                        .eq(KnowledgeDocumentEntity::getDatasetId, datasetId)
                        .eq(KnowledgeDocumentEntity::getId, documentId).last("LIMIT 1"));
        if (document == null) {
            throw new PlatformException("document_not_found", "Document not found", null);
        }
        return document;
    }

    public SegmentEntity requireSegment(String datasetId, String documentId, String segmentId) {
        return requireSegment(UserContextHolder.currentTenantId(), datasetId, documentId, segmentId);
    }

    public SegmentEntity requireSegment(String tenantId, String datasetId, String documentId, String segmentId) {
        SegmentEntity segment = indexingService.getSegment(tenantId, segmentId);
        if (segment == null || !datasetId.equals(segment.getDatasetId())
                || !documentId.equals(segment.getDocumentId())) {
            throw new PlatformException("segment_not_found", "Segment not found", null);
        }
        return segment;
    }

    public KnowledgeDocumentEntity addText(String datasetId, String name, String text) {
        return ingestionService.addText(datasetId, name, text);
    }

    public KnowledgeDocumentEntity addText(String tenantId, String datasetId, String name, String text) {
        requireDataset(tenantId, datasetId);
        return ingestionService.addText(tenantId, datasetId, name, text);
    }

    public KnowledgeDocumentEntity addMarkdown(String datasetId, String name, String markdown) {
        return ingestionService.addMarkdown(datasetId, name, markdown);
    }

    public KnowledgeDocumentEntity addMarkdown(String tenantId, String datasetId,
                                                String name, String markdown) {
        requireDataset(tenantId, datasetId);
        return ingestionService.addMarkdown(tenantId, datasetId, name, markdown);
    }

    public KnowledgeDocumentEntity addFile(String datasetId, String filename, byte[] bytes) {
        return ingestionService.addFile(datasetId, filename, bytes);
    }

    public KnowledgeDocumentEntity addFile(String tenantId, String datasetId,
                                            String filename, byte[] bytes) {
        requireDataset(tenantId, datasetId);
        return ingestionService.addFile(tenantId, datasetId, filename, bytes);
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
        String tenantId = UserContextHolder.currentTenantId();
        KnowledgeDocumentEntity document = documentMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                        .eq(KnowledgeDocumentEntity::getTenantId, tenantId)
                        .eq(KnowledgeDocumentEntity::getId, documentId).last("LIMIT 1"));
        if (document == null) {
            return;
        }
        DatasetEntity dataset = datasetService.require(
                tenantId,
                document.getDatasetId());
        deleteDocument(dataset, document);
    }

    public void deleteDocument(String tenantId, String datasetId, String documentId) {
        DatasetEntity dataset = requireDataset(tenantId, datasetId);
        KnowledgeDocumentEntity document = requireDocument(tenantId, datasetId, documentId);
        deleteDocument(dataset, document);
    }

    private void deleteDocument(DatasetEntity dataset, KnowledgeDocumentEntity document) {
        indexingService.removeDocument(dataset, document.getId());
        documentMapper.delete(new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                .eq(KnowledgeDocumentEntity::getTenantId, document.getTenantId())
                .eq(KnowledgeDocumentEntity::getId, document.getId()));
        datasetService.applyCountChange(dataset,
                DatasetCountChange.documentRemoved(valueOrZero(document.getSegmentCount())));
    }

    public record KnowledgeGraphNode(String id, String type, String label,
                                    String datasetId, String documentId, String segmentId,
                                    String headingPath, String source, String description,
                                    int weight, List<String> evidenceSegmentIds) {
    }

    public record KnowledgeGraphEdge(String source, String target, String relation,
                                     int weight, List<String> evidenceSegmentIds) {
    }

    public record KnowledgeGraph(String datasetId,
                                List<KnowledgeGraphNode> nodes,
                                List<KnowledgeGraphEdge> edges) {
    }

    public List<KnowledgeDocumentEntity> listDocuments(String datasetId) {
        return documentMapper.selectList(new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                .eq(KnowledgeDocumentEntity::getTenantId, UserContextHolder.currentTenantId())
                .eq(KnowledgeDocumentEntity::getDatasetId, datasetId));
    }

    public List<KnowledgeDocumentEntity> listDocuments(String tenantId, String datasetId) {
        requireDataset(tenantId, datasetId);
        return documentMapper.selectList(new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                .eq(KnowledgeDocumentEntity::getTenantId, tenantId)
                .eq(KnowledgeDocumentEntity::getDatasetId, datasetId));
    }

    public KnowledgeDocumentEntity getDocument(String documentId) {
        return documentMapper.selectOne(new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                .eq(KnowledgeDocumentEntity::getTenantId, UserContextHolder.currentTenantId())
                .eq(KnowledgeDocumentEntity::getId, documentId).last("LIMIT 1"));
    }

    public KnowledgeDocumentEntity getDocument(String tenantId, String datasetId, String documentId) {
        requireDataset(tenantId, datasetId);
        return requireDocument(tenantId, datasetId, documentId);
    }

    public ParsedDocument getParsedDocument(String documentId) {
        return JsonUtils.parse(documentMapper.selectParsedDocumentJson(
                UserContextHolder.currentTenantId(), documentId), ParsedDocument.class);
    }

    public ParsedDocument getParsedDocument(String tenantId, String datasetId, String documentId) {
        getDocument(tenantId, datasetId, documentId);
        return JsonUtils.parse(documentMapper.selectParsedDocumentJson(tenantId, documentId), ParsedDocument.class);
    }

    public KnowledgeDocumentEntity reparseDocument(String datasetId, String documentId) {
        String encoded = documentMapper.selectSourceDataBase64(UserContextHolder.currentTenantId(), documentId);
        if (encoded == null || encoded.isBlank()) return null;
        return ingestionService.reparse(datasetId, documentId, Base64.getDecoder().decode(encoded));
    }

    public KnowledgeDocumentEntity reparseDocument(String tenantId, String datasetId, String documentId) {
        getDocument(tenantId, datasetId, documentId);
        String encoded = documentMapper.selectSourceDataBase64(tenantId, documentId);
        if (encoded == null || encoded.isBlank()) return null;
        return ingestionService.reparse(tenantId, datasetId, documentId, Base64.getDecoder().decode(encoded));
    }

    public List<SegmentEntity> listSegments(String documentId, int page, int pageSize) {
        int pageNumber = Math.max(1, page);
        int boundedPageSize = Math.min(200, Math.max(1, pageSize));
        return indexingService.listSegments(documentId, pageNumber, boundedPageSize);
    }

    public List<SegmentEntity> listSegments(String tenantId, String datasetId, String documentId,
                                            int page, int pageSize) {
        getDocument(tenantId, datasetId, documentId);
        int pageNumber = Math.max(1, page);
        int boundedPageSize = Math.min(200, Math.max(1, pageSize));
        return indexingService.listSegments(tenantId, documentId, pageNumber, boundedPageSize);
    }

    public SegmentEntity updateSegment(String datasetId, String segmentId, String newContent) {
        DatasetEntity dataset = datasetService.require(datasetId);
        return indexingService.updateSegment(dataset, segmentId, newContent);
    }

    public SegmentEntity updateSegment(String tenantId, String datasetId, String documentId,
                                       String segmentId, String newContent) {
        DatasetEntity dataset = requireDataset(tenantId, datasetId);
        requireDocument(tenantId, datasetId, documentId);
        requireSegment(tenantId, datasetId, documentId, segmentId);
        return indexingService.updateSegment(dataset, segmentId, newContent);
    }

    public void deleteSegment(String datasetId, String segmentId) {
        DatasetEntity dataset = datasetService.require(datasetId);
        indexingService.deleteSegment(dataset, segmentId);
    }

    public void deleteSegment(String tenantId, String datasetId, String documentId, String segmentId) {
        DatasetEntity dataset = requireDataset(tenantId, datasetId);
        requireDocument(tenantId, datasetId, documentId);
        requireSegment(tenantId, datasetId, documentId, segmentId);
        indexingService.deleteSegment(dataset, segmentId);
    }

    public SegmentEntity setSegmentEnabled(String datasetId, String segmentId, boolean enabled) {
        DatasetEntity dataset = datasetService.require(datasetId);
        return indexingService.setSegmentEnabled(dataset, segmentId, enabled);
    }

    public SegmentEntity setSegmentEnabled(String tenantId, String datasetId, String documentId,
                                           String segmentId, boolean enabled) {
        DatasetEntity dataset = requireDataset(tenantId, datasetId);
        requireDocument(tenantId, datasetId, documentId);
        requireSegment(tenantId, datasetId, documentId, segmentId);
        return indexingService.setSegmentEnabled(dataset, segmentId, enabled);
    }

    public int reindexDocument(String datasetId, String documentId) {
        DatasetEntity dataset = datasetService.require(datasetId);
        return indexingService.reembedDocument(dataset, documentId);
    }

    public int reindexDocument(String tenantId, String datasetId, String documentId) {
        DatasetEntity dataset = requireDataset(tenantId, datasetId);
        requireDocument(tenantId, datasetId, documentId);
        return indexingService.reembedDocument(dataset, documentId);
    }

    public SegmentEntity appendSegment(String datasetId, String documentId, String content) {
        DatasetEntity dataset = datasetService.require(datasetId);
        KnowledgeDocumentEntity document = requireDocument(UserContextHolder.currentTenantId(), datasetId, documentId);
        if (document == null) {
            return null;
        }

        SegmentEntity segment = indexingService.appendSegment(dataset, document, content);
        document.setSegmentCount(valueOrZero(document.getSegmentCount()) + 1);
        updateOwned(document);
        datasetService.applyCountChange(dataset, DatasetCountChange.segmentAdded());
        return segment;
    }

    public SegmentEntity appendSegment(String tenantId, String datasetId, String documentId,
                                       String content) {
        DatasetEntity dataset = requireDataset(tenantId, datasetId);
        KnowledgeDocumentEntity document = requireDocument(tenantId, datasetId, documentId);
        SegmentEntity segment = indexingService.appendSegment(dataset, document, content);
        document.setSegmentCount(valueOrZero(document.getSegmentCount()) + 1);
        updateOwned(document);
        datasetService.applyCountChange(dataset, DatasetCountChange.segmentAdded());
        return segment;
    }

    public List<RetrievedSegment> retrieve(String datasetId, RetrievalRequest request) {
        DatasetEntity dataset = datasetService.require(datasetId);
        return retrieve(dataset, request);
    }

    private List<RetrievedSegment> retrieve(DatasetEntity dataset, RetrievalRequest request) {
        long startedAt = System.currentTimeMillis();
        List<RetrievedSegment> retrievedSegments = retriever.retrieve(
                dataset, datasetService.retrievalConfig(dataset), request);
        recordHitTest(dataset, request, retrievedSegments,
                (int) (System.currentTimeMillis() - startedAt));
        return retrievedSegments;
    }

    public List<RetrievedSegment> retrieve(String tenantId, String datasetId, RetrievalRequest request) {
        return retrieve(requireDataset(tenantId, datasetId), request);
    }

    public List<RetrievedSegment> retrieve(String datasetId, String query) {
        return retrieve(datasetId, RetrievalRequest.builder().query(query).build());
    }

    public List<RetrievedSegment> retrieve(String tenantId, String datasetId, String query) {
        return retrieve(tenantId, datasetId, RetrievalRequest.builder().query(query).build());
    }

    public List<RetrievedSegment> retrieve(List<String> datasetIds, RetrievalRequest request) {
        return retrieve(UserContextHolder.currentTenantId(), datasetIds, request);
    }

    public List<RetrievedSegment> retrieve(String tenantId, List<String> datasetIds,
                                           RetrievalRequest request) {
        int resultLimit = request.getTopK() != null ? request.getTopK() : 5;
        return datasetIds.stream()
                .flatMap(datasetId -> retrieve(tenantId, datasetId, request).stream())
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
                .eq(HitTestingLogEntity::getTenantId, UserContextHolder.currentTenantId())
                .eq(HitTestingLogEntity::getDatasetId, datasetId)
                .orderByDesc(HitTestingLogEntity::getCreatedAt)
                .last("limit " + boundedLimit));
    }

    public List<HitTestingLogEntity> listHitTestingHistory(String tenantId, String datasetId, int limit) {
        requireDataset(tenantId, datasetId);
        if (hitTestingLogMapper == null) return List.of();
        int boundedLimit = Math.min(500, Math.max(1, limit));
        return hitTestingLogMapper.selectList(new LambdaQueryWrapper<HitTestingLogEntity>()
                .eq(HitTestingLogEntity::getTenantId, tenantId)
                .eq(HitTestingLogEntity::getDatasetId, datasetId)
                .orderByDesc(HitTestingLogEntity::getCreatedAt)
                .last("limit " + boundedLimit));
    }

    public KnowledgeGraph buildKnowledgeGraph(String tenantId, String datasetId) {
        requireDataset(tenantId, datasetId);
        List<SegmentEntity> segments = indexingService.listSegmentsByDataset(tenantId, datasetId);
        if (segments.isEmpty()) {
            return new KnowledgeGraph(datasetId, List.of(), List.of());
        }

        Map<String, KnowledgeGraphNode> nodes = new LinkedHashMap<>();
        Set<String> edgeSignatures = new java.util.LinkedHashSet<>();
        List<KnowledgeGraphEdge> edges = new ArrayList<>();
        Map<String, String> lastSegmentPerDocument = new HashMap<>();

        for (SegmentEntity segment : segments) {
            Map<String, Object> metadata = JsonUtils.parseMap(segment.getMetadataJson());
            String heading = sanitizeNodeLabel(metadata.get("heading"));
            String documentName = sanitizeNodeLabel(metadata.get("documentName"));
            String source = sanitizeNodeLabel(metadata.get("source"));
            String blockType = sanitizeNodeLabel(metadata.get("blockType"));

            String documentNodeId = "document:" + segment.getDocumentId();
            nodes.putIfAbsent(documentNodeId, new KnowledgeGraphNode(
                    documentNodeId,
                    "document",
                    documentName == null
                            ? "document " + segment.getDocumentId()
                            : documentName,
                    datasetId,
                    segment.getDocumentId(),
                    null,
                    null,
                    source,
                    "文档来源节点",
                    1,
                    List.of(segment.getId())));

            String segmentNodeId = "segment:" + segment.getId();
            nodes.put(segmentNodeId, new KnowledgeGraphNode(
                    segmentNodeId,
                    "segment",
                    buildSegmentLabel(segment, heading, blockType),
                    datasetId,
                    segment.getDocumentId(),
                    segment.getId(),
                    heading,
                    source,
                    sanitizeNodeLabel(segment.getContent()),
                    1,
                    List.of(segment.getId())));
            addEdge(edges, edgeSignatures, documentNodeId, segmentNodeId, "contains", segment.getId());

            String prevSegment = lastSegmentPerDocument.get(segment.getDocumentId());
            if (prevSegment != null) {
                addEdge(edges, edgeSignatures, prevSegment, segmentNodeId, "next", segment.getId());
            }
            lastSegmentPerDocument.put(segment.getDocumentId(), segmentNodeId);

            if (heading != null) {
                addHeadingChain(nodes, edges, edgeSignatures, documentNodeId,
                        datasetId, segment.getDocumentId(), heading, source);
                String leafHeadingId = headingNodeId(datasetId, segment.getDocumentId(), heading);
                addEdge(edges, edgeSignatures, leafHeadingId, segmentNodeId, "covers", segment.getId());
            }
        }

        addSemanticLayer(segments, nodes, edges, edgeSignatures, datasetId);

        return new KnowledgeGraph(datasetId, new ArrayList<>(nodes.values()), edges);
    }

    private static void addHeadingChain(Map<String, KnowledgeGraphNode> nodes,
                                       List<KnowledgeGraphEdge> edges,
                                       Set<String> edgeSignatures,
                                       String documentNodeId,
                                       String datasetId,
                                       String documentId,
                                       String heading,
                                       String source) {
        String[] parts = heading.split("\\s*>\\s*");
        String parent = documentNodeId;
        String path = "";
        for (String rawPart : parts) {
            String part = rawPart == null ? "" : rawPart.trim();
            if (part.isBlank()) continue;
            path = path.isEmpty() ? part : path + " > " + part;
            String headingId = headingNodeId(datasetId, documentId, path);
            nodes.putIfAbsent(headingId, new KnowledgeGraphNode(
                    headingId,
                    "heading",
                    part,
                    datasetId,
                    documentId,
                    null,
                    path,
                    source,
                    "文档标题层级",
                    1,
                    List.of()));
            addEdge(edges, edgeSignatures,
                    parent,
                    headingId,
                    parent.equals(documentNodeId) ? "has_heading" : "child_heading", null);
            parent = headingId;
        }
    }

    private static void addEdge(List<KnowledgeGraphEdge> edges,
                                Set<String> signatures,
                                String source, String target, String relation,
                                String evidenceSegmentId) {
        String signature = source + "|" + target + "|" + relation;
        if (signatures.add(signature)) {
            edges.add(new KnowledgeGraphEdge(source, target, relation, 1,
                    evidenceSegmentId == null ? List.of() : List.of(evidenceSegmentId)));
        }
    }

    /** Adds a traceable semantic layer inspired by RAGFlow GraphRAG. */
    private static void addSemanticLayer(List<SegmentEntity> segments,
                                         Map<String, KnowledgeGraphNode> nodes,
                                         List<KnowledgeGraphEdge> edges,
                                         Set<String> edgeSignatures,
                                         String datasetId) {
        Map<String, ConceptEvidence> concepts = new LinkedHashMap<>();
        Map<String, List<String>> conceptsBySegment = new LinkedHashMap<>();
        for (SegmentEntity segment : segments) {
            LinkedHashSet<String> segmentConcepts = extractConcepts(segment);
            conceptsBySegment.put(segment.getId(), new ArrayList<>(segmentConcepts));
            for (String label : segmentConcepts) {
                String key = normalizeConcept(label);
                ConceptEvidence evidence = concepts.computeIfAbsent(key,
                        ignored -> new ConceptEvidence(label));
                evidence.mentions++;
                evidence.segmentIds.add(segment.getId());
            }
        }

        Set<String> selected = concepts.entrySet().stream()
                .filter(entry -> entry.getValue().mentions >= 2 || isDistinctiveConcept(entry.getValue().label))
                .sorted(Comparator
                        .<Map.Entry<String, ConceptEvidence>>comparingInt(entry -> entry.getValue().mentions)
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(80)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        for (String key : selected) {
            ConceptEvidence evidence = concepts.get(key);
            String nodeId = entityNodeId(datasetId, key);
            nodes.put(nodeId, new KnowledgeGraphNode(
                    nodeId, "entity", evidence.label, datasetId,
                    null, null, null, "keyword-index",
                    "在 " + evidence.segmentIds.size() + " 个片段中出现的语义概念",
                    evidence.mentions, List.copyOf(evidence.segmentIds)));
        }

        Map<String, WeightedEdge> semanticEdges = new LinkedHashMap<>();
        for (SegmentEntity segment : segments) {
            List<String> selectedForSegment = conceptsBySegment.getOrDefault(segment.getId(), List.of())
                    .stream().map(KnowledgeService::normalizeConcept).filter(selected::contains)
                    .distinct().limit(6).toList();
            for (String concept : selectedForSegment) {
                addEdge(edges, edgeSignatures, "segment:" + segment.getId(),
                        entityNodeId(datasetId, concept), "mentions", segment.getId());
            }
            for (int i = 0; i < selectedForSegment.size(); i++) {
                for (int j = i + 1; j < selectedForSegment.size(); j++) {
                    String first = selectedForSegment.get(i);
                    String second = selectedForSegment.get(j);
                    String left = first.compareTo(second) <= 0 ? first : second;
                    String right = first.compareTo(second) <= 0 ? second : first;
                    String signature = left + "|" + right;
                    WeightedEdge edge = semanticEdges.computeIfAbsent(signature,
                            ignored -> new WeightedEdge(left, right));
                    edge.weight++;
                    edge.segmentIds.add(segment.getId());
                }
            }
        }
        semanticEdges.values().stream()
                .sorted(Comparator.comparingInt((WeightedEdge edge) -> edge.weight).reversed())
                .limit(160)
                .forEach(edge -> edges.add(new KnowledgeGraphEdge(
                        entityNodeId(datasetId, edge.source), entityNodeId(datasetId, edge.target),
                        "co_occurs", edge.weight, List.copyOf(edge.segmentIds))));
    }

    private static LinkedHashSet<String> extractConcepts(SegmentEntity segment) {
        LinkedHashSet<String> concepts = new LinkedHashSet<>();
        Map<String, Object> metadata = JsonUtils.parseMap(segment.getMetadataJson());
        String heading = sanitizeNodeLabel(metadata.get("heading"));
        if (heading != null) {
            for (String part : heading.split("\\s*>\\s*")) {
                if (validConcept(part)) concepts.add(part.trim());
            }
        }
        Object explicit = metadata.get("entities");
        if (explicit instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                String value = sanitizeNodeLabel(item);
                if (validConcept(value)) concepts.add(value);
            }
        }
        for (String token : KeywordTokenizer.tokenize(segment.getContent())) {
            if (validConcept(token)) concepts.add(token);
            if (concepts.size() >= 18) break;
        }
        return concepts;
    }

    private static boolean validConcept(String value) {
        if (value == null) return false;
        String text = value.trim();
        if (text.length() < 2 || text.length() > 48 || text.chars().allMatch(Character::isDigit)) return false;
        return !Set.of("这个", "一个", "可以", "进行", "使用", "以及", "通过", "对于", "我们", "你们",
                "that", "this", "from", "into", "when", "then", "have", "will").contains(text.toLowerCase(Locale.ROOT));
    }

    private static boolean isDistinctiveConcept(String value) {
        return value != null && value.length() >= 4
                && value.chars().anyMatch(ch -> ch < 128 && Character.isLetter(ch));
    }

    private static String normalizeConcept(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private static String entityNodeId(String datasetId, String concept) {
        return "entity:" + datasetId + ":" + Integer.toUnsignedString(concept.hashCode(), 36);
    }

    private static final class ConceptEvidence {
        private final String label;
        private int mentions;
        private final LinkedHashSet<String> segmentIds = new LinkedHashSet<>();

        private ConceptEvidence(String label) {
            this.label = label;
        }
    }

    private static final class WeightedEdge {
        private final String source;
        private final String target;
        private int weight;
        private final LinkedHashSet<String> segmentIds = new LinkedHashSet<>();

        private WeightedEdge(String source, String target) {
            this.source = source;
            this.target = target;
        }
    }

    private static String headingNodeId(String datasetId, String documentId, String headingPath) {
        return "heading:" + datasetId + ":" + documentId + ":" + Math.abs(headingPath.hashCode());
    }

    private static String buildSegmentLabel(SegmentEntity segment, String heading, String blockType) {
        String base = sanitizeNodeLabel(segment.getContent());
        StringBuilder label = new StringBuilder();
        if (heading != null) {
            label.append(heading).append(" / ");
        }
        if (blockType != null) {
            label.append('[').append(blockType).append("] ");
        }
        label.append(base == null || base.isBlank() ? "segment" : base);
        String text = label.toString().trim();
        return text.length() > 80 ? text.substring(0, 80) + "…" : text;
    }

    private static String sanitizeNodeLabel(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
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
            item.put("vectorScore", segment.getVectorScore());
            item.put("keywordScore", segment.getKeywordScore());
            item.put("documentName",
                    segment.getMetadata() == null
                            ? null
                            : segment.getMetadata().get("documentName"));
            item.put("blockType", segment.getMetadata() == null
                    ? null
                    : segment.getMetadata().get("blockType"));
            item.put("headingPath", segment.getMetadata() == null
                    ? null
                    : segment.getMetadata().get("heading"));
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

    private void updateOwned(KnowledgeDocumentEntity document) {
        documentMapper.update(document, new LambdaUpdateWrapper<KnowledgeDocumentEntity>()
                .eq(KnowledgeDocumentEntity::getTenantId, document.getTenantId())
                .eq(KnowledgeDocumentEntity::getId, document.getId()));
    }
}
