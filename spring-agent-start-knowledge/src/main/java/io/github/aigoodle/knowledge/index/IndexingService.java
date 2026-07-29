package io.github.aigoodle.knowledge.index;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.knowledge.chunk.Chunk;
import io.github.aigoodle.knowledge.entity.DatasetEntity;
import io.github.aigoodle.knowledge.entity.KnowledgeDocumentEntity;
import io.github.aigoodle.knowledge.entity.SegmentEntity;
import io.github.aigoodle.knowledge.mapper.SegmentMapper;
import io.github.aigoodle.knowledge.nlp.KeywordTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persists chunks as segments and, for high-quality datasets, embeds and stores them
 * in the dataset's vector store.
 */
public class IndexingService {

    private static final Logger log = LoggerFactory.getLogger(IndexingService.class);

    private final SegmentMapper segmentMapper;
    private final VectorStoreManager vectorStoreManager;

    public IndexingService(SegmentMapper segmentMapper, VectorStoreManager vectorStoreManager) {
        this.segmentMapper = segmentMapper;
        this.vectorStoreManager = vectorStoreManager;
    }

    public int index(DatasetEntity dataset, KnowledgeDocumentEntity document, List<Chunk> chunks) {
        List<SegmentEntity> segments = new ArrayList<>();
        List<Document> vectorDocs = new ArrayList<>();
        boolean vectorize = vectorStoreManager.hasVectorIndex(dataset);

        for (Chunk chunk : chunks) {
            String vectorId = UUID.randomUUID().toString();
            SegmentEntity seg = new SegmentEntity();
            seg.setTenantId(dataset.getTenantId());
            seg.setDatasetId(dataset.getId());
            seg.setDocumentId(document.getId());
            seg.setPosition(chunk.getPosition());
            seg.setContent(chunk.getContent());
            seg.setTokenCount(chunk.tokenCount());
            seg.setKeywords(KeywordTokenizer.join(chunk.getContent()));
            seg.setVectorId(vectorId);
            seg.setEnabled(Boolean.TRUE);
            seg.setHash(Integer.toHexString(chunk.getContent().hashCode()));

            Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
            if (chunk.getParentContent() != null) {
                metadata.put("parentContent", chunk.getParentContent());
            }
            seg.setMetadataJson(JsonUtils.toJson(metadata));
            segmentMapper.insert(seg);
            segments.add(seg);

            if (vectorize) {
                Map<String, Object> docMeta = new HashMap<>(metadata);
                docMeta.put("segmentId", seg.getId());
                docMeta.put("datasetId", dataset.getId());
                docMeta.put("documentId", document.getId());
                docMeta.put("position", chunk.getPosition());
                vectorDocs.add(Document.builder()
                        .id(vectorId)
                        .text(chunk.getContent())
                        .metadata(docMeta)
                        .build());
            }
        }

        if (vectorize && !vectorDocs.isEmpty()) {
            log.debug("Embedding + storing {} vectors for document {}", vectorDocs.size(), document.getId());
            vectorStoreManager.getStore(dataset).add(vectorDocs);
        }
        return segments.size();
    }

    /** Read paginated segments for a document — powers the frontend "chunks" tab. */
    public List<SegmentEntity> listSegments(String documentId, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        return segmentMapper.selectList(new LambdaQueryWrapper<SegmentEntity>()
                .eq(SegmentEntity::getDocumentId, documentId)
                .orderByAsc(SegmentEntity::getPosition)
                .last("limit " + pageSize + " offset " + offset));
    }

    public void removeDocument(DatasetEntity dataset, String documentId) {
        List<SegmentEntity> segments = segmentMapper.selectList(new LambdaQueryWrapper<SegmentEntity>()
                .eq(SegmentEntity::getDocumentId, documentId));
        if (segments.isEmpty()) {
            return;
        }
        if (vectorStoreManager.hasVectorIndex(dataset)) {
            List<String> vectorIds = segments.stream().map(SegmentEntity::getVectorId).toList();
            try {
                vectorStoreManager.getStore(dataset).delete(vectorIds);
            } catch (Exception e) {
                log.warn("Failed to delete vectors for document {}: {}", documentId, e.getMessage());
            }
        }
        segmentMapper.delete(new LambdaQueryWrapper<SegmentEntity>()
                .eq(SegmentEntity::getDocumentId, documentId));
    }

    /** Edit a segment's content: re-embed and swap the vector in the store. */
    public SegmentEntity updateSegment(DatasetEntity dataset, String segmentId, String newContent) {
        SegmentEntity seg = segmentMapper.selectById(segmentId);
        if (seg == null) {
            return null;
        }
        seg.setContent(newContent);
        seg.setKeywords(KeywordTokenizer.join(newContent));
        seg.setHash(Integer.toHexString(newContent.hashCode()));
        // Content changed -> token count is a coarse re-estimate matching the ingest path
        seg.setTokenCount(newContent == null ? 0 : Math.max(1, newContent.length() / 4));
        segmentMapper.updateById(seg);

        if (Boolean.TRUE.equals(seg.getEnabled()) && vectorStoreManager.hasVectorIndex(dataset)) {
            try {
                vectorStoreManager.getStore(dataset).delete(List.of(seg.getVectorId()));
            } catch (Exception e) {
                log.warn("Failed to delete old vector for segment {}: {}", segmentId, e.getMessage());
            }
            Map<String, Object> meta = decodeMeta(seg.getMetadataJson());
            meta.put("segmentId", seg.getId());
            meta.put("datasetId", dataset.getId());
            meta.put("documentId", seg.getDocumentId());
            meta.put("position", seg.getPosition());
            vectorStoreManager.getStore(dataset).add(List.of(Document.builder()
                    .id(seg.getVectorId())
                    .text(newContent)
                    .metadata(meta)
                    .build()));
        }
        return seg;
    }

    /** Delete a single segment (and its vector) without touching sibling segments. */
    public void deleteSegment(DatasetEntity dataset, String segmentId) {
        SegmentEntity seg = segmentMapper.selectById(segmentId);
        if (seg == null) {
            return;
        }
        if (vectorStoreManager.hasVectorIndex(dataset) && seg.getVectorId() != null) {
            try {
                vectorStoreManager.getStore(dataset).delete(List.of(seg.getVectorId()));
            } catch (Exception e) {
                log.warn("Failed to delete vector for segment {}: {}", segmentId, e.getMessage());
            }
        }
        segmentMapper.deleteById(segmentId);
    }

    /**
     * Flip a segment's enabled flag: when disabled its vector is removed from the store
     * so it can no longer be retrieved; when re-enabled we re-embed and add it back.
     */
    public SegmentEntity setSegmentEnabled(DatasetEntity dataset, String segmentId, boolean enabled) {
        SegmentEntity seg = segmentMapper.selectById(segmentId);
        if (seg == null) {
            return null;
        }
        if (Boolean.TRUE.equals(seg.getEnabled()) == enabled) {
            return seg;
        }
        seg.setEnabled(enabled);
        segmentMapper.updateById(seg);

        if (vectorStoreManager.hasVectorIndex(dataset)) {
            try {
                if (enabled) {
                    Map<String, Object> meta = decodeMeta(seg.getMetadataJson());
                    meta.put("segmentId", seg.getId());
                    meta.put("datasetId", dataset.getId());
                    meta.put("documentId", seg.getDocumentId());
                    meta.put("position", seg.getPosition());
                    vectorStoreManager.getStore(dataset).add(List.of(Document.builder()
                            .id(seg.getVectorId())
                            .text(seg.getContent())
                            .metadata(meta)
                            .build()));
                } else {
                    vectorStoreManager.getStore(dataset).delete(List.of(seg.getVectorId()));
                }
            } catch (Exception e) {
                log.warn("Failed to sync vector store for segment {}: {}", segmentId, e.getMessage());
            }
        }
        return seg;
    }

    public SegmentEntity getSegment(String segmentId) {
        return segmentMapper.selectById(segmentId);
    }

    /**
     * Append a manually-authored chunk to an existing document, embed it, and
     * insert it into the dataset's vector store. Position is set to
     * {@code max(existing) + 1} so the new chunk sits at the end.
     */
    public SegmentEntity appendSegment(DatasetEntity dataset, KnowledgeDocumentEntity document, String content) {
        List<SegmentEntity> existing = segmentMapper.selectList(new LambdaQueryWrapper<SegmentEntity>()
                .eq(SegmentEntity::getDocumentId, document.getId()));
        int position = existing.stream()
                .mapToInt(s -> s.getPosition() == null ? 0 : s.getPosition())
                .max()
                .orElse(-1) + 1;

        String vectorId = UUID.randomUUID().toString();
        SegmentEntity seg = new SegmentEntity();
        seg.setTenantId(dataset.getTenantId());
        seg.setDatasetId(dataset.getId());
        seg.setDocumentId(document.getId());
        seg.setPosition(position);
        seg.setContent(content);
        seg.setTokenCount(content == null ? 0 : Math.max(1, content.length() / 4));
        seg.setKeywords(KeywordTokenizer.join(content));
        seg.setVectorId(vectorId);
        seg.setEnabled(Boolean.TRUE);
        seg.setHash(Integer.toHexString(content == null ? 0 : content.hashCode()));
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentName", document.getName());
        metadata.put("source", "manual");
        seg.setMetadataJson(JsonUtils.toJson(metadata));
        segmentMapper.insert(seg);

        if (vectorStoreManager.hasVectorIndex(dataset)) {
            Map<String, Object> docMeta = new HashMap<>(metadata);
            docMeta.put("segmentId", seg.getId());
            docMeta.put("datasetId", dataset.getId());
            docMeta.put("documentId", document.getId());
            docMeta.put("position", position);
            try {
                vectorStoreManager.getStore(dataset).add(List.of(Document.builder()
                        .id(vectorId)
                        .text(content)
                        .metadata(docMeta)
                        .build()));
            } catch (Exception e) {
                log.warn("Failed to add vector for new segment {}: {}", seg.getId(), e.getMessage());
            }
        }
        return seg;
    }

    /**
     * Re-embed every enabled segment of a document. Segment content stays as-is —
     * this is for "I swapped the embedding model, please refresh the vectors" and
     * for recovering from a partial index failure.
     */
    public int reembedDocument(DatasetEntity dataset, String documentId) {
        List<SegmentEntity> segments = segmentMapper.selectList(new LambdaQueryWrapper<SegmentEntity>()
                .eq(SegmentEntity::getDocumentId, documentId));
        if (segments.isEmpty() || !vectorStoreManager.hasVectorIndex(dataset)) {
            return 0;
        }
        // First evict every old vector — the store might have entries for now-disabled
        // segments too, so drop everything for this document.
        List<String> vectorIds = segments.stream().map(SegmentEntity::getVectorId).toList();
        try {
            vectorStoreManager.getStore(dataset).delete(vectorIds);
        } catch (Exception e) {
            log.warn("Failed to purge old vectors for document {}: {}", documentId, e.getMessage());
        }

        List<Document> vectorDocs = new ArrayList<>();
        for (SegmentEntity seg : segments) {
            if (!Boolean.TRUE.equals(seg.getEnabled())) {
                continue;
            }
            Map<String, Object> meta = new HashMap<>();
            String metaJson = seg.getMetadataJson();
            if (metaJson != null && !metaJson.isBlank()) {
                try {
                    meta.putAll(JsonUtils.parseMap(metaJson));
                } catch (Exception ignore) {
                    // metadata JSON corrupted — proceed with empty meta rather than fail the whole re-embed
                }
            }
            meta.put("segmentId", seg.getId());
            meta.put("datasetId", dataset.getId());
            meta.put("documentId", seg.getDocumentId());
            meta.put("position", seg.getPosition());
            vectorDocs.add(Document.builder()
                    .id(seg.getVectorId())
                    .text(seg.getContent())
                    .metadata(meta)
                    .build());
        }
        if (!vectorDocs.isEmpty()) {
            vectorStoreManager.getStore(dataset).add(vectorDocs);
        }
        return vectorDocs.size();
    }

    private Map<String, Object> decodeMeta(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return new HashMap<>(JsonUtils.parseMap(json));
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
}
