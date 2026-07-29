package io.github.aigoodle.knowledge.retrieve;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.knowledge.config.RetrievalConfig;
import io.github.aigoodle.knowledge.entity.DatasetEntity;
import io.github.aigoodle.knowledge.entity.SegmentEntity;
import io.github.aigoodle.knowledge.enums.RetrievalMethod;
import io.github.aigoodle.knowledge.index.VectorStoreManager;
import io.github.aigoodle.knowledge.mapper.SegmentMapper;
import io.github.aigoodle.knowledge.nlp.KeywordTokenizer;
import io.github.aigoodle.knowledge.rerank.NoopReranker;
import io.github.aigoodle.knowledge.rerank.Reranker;
import io.github.aigoodle.knowledge.rerank.RerankerRegistry;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fuses dense vector similarity with sparse keyword overlap, the way RAGFlow/Dify
 * combine embedding and full-text recall. Vector scores are cosine similarity from
 * the store; keyword scores are query-token coverage. Both live in [0,1] and are
 * blended by the configured weight. When reranking is enabled the fused candidates
 * are passed through the {@link Reranker} chosen by {@link RetrievalConfig}.
 */
public class HybridRetriever {

    private final SegmentMapper segmentMapper;
    private final VectorStoreManager vectorStoreManager;
    private final RerankerRegistry rerankerRegistry;

    public HybridRetriever(SegmentMapper segmentMapper, VectorStoreManager vectorStoreManager) {
        this(segmentMapper, vectorStoreManager, new RerankerRegistry(List.of(), new NoopReranker()));
    }

    public HybridRetriever(SegmentMapper segmentMapper, VectorStoreManager vectorStoreManager,
                           RerankerRegistry rerankerRegistry) {
        this.segmentMapper = segmentMapper;
        this.vectorStoreManager = vectorStoreManager;
        this.rerankerRegistry = rerankerRegistry;
    }

    public List<RetrievedSegment> retrieve(DatasetEntity dataset, RetrievalConfig cfg, RetrievalRequest req) {
        RetrievalMethod method = req.getMethod() != null ? req.getMethod() : cfg.getMethod();
        int topK = req.getTopK() != null ? req.getTopK() : cfg.getTopK();
        double threshold = req.getScoreThreshold() != null ? req.getScoreThreshold() : cfg.getScoreThreshold();
        double vectorWeight = req.getVectorWeight() != null ? req.getVectorWeight() : cfg.getVectorWeight();
        double keywordWeight = Math.max(0.0, 1.0 - vectorWeight);

        boolean hasVector = vectorStoreManager.hasVectorIndex(dataset);
        if (method == RetrievalMethod.VECTOR && !hasVector) {
            method = RetrievalMethod.FULL_TEXT; // economy dataset: degrade gracefully
        }

        int recallK = cfg.isRerankEnabled() ? Math.max(topK, cfg.getRerankPoolSize()) : Math.max(topK * 4, topK);

        Map<String, Double> vectorScores = new HashMap<>();
        if (method != RetrievalMethod.FULL_TEXT && hasVector) {
            SearchRequest sr = SearchRequest.builder()
                    .query(req.getQuery())
                    .topK(recallK)
                    .similarityThreshold(0.0)
                    .build();
            List<Document> docs = vectorStoreManager.getStore(dataset).similaritySearch(sr);
            if (docs != null) {
                for (Document d : docs) {
                    Object sid = d.getMetadata().get("segmentId");
                    if (sid != null) {
                        vectorScores.put(sid.toString(), d.getScore() == null ? 0.0 : d.getScore());
                    }
                }
            }
        }

        Map<String, Double> keywordScores = new HashMap<>();
        if (method != RetrievalMethod.VECTOR) {
            Set<String> queryTokens = new LinkedHashSet<>(KeywordTokenizer.tokenize(req.getQuery()));
            if (!queryTokens.isEmpty()) {
                List<SegmentEntity> segments = segmentMapper.selectList(new LambdaQueryWrapper<SegmentEntity>()
                        .eq(SegmentEntity::getDatasetId, dataset.getId())
                        .eq(SegmentEntity::getEnabled, true));
                for (SegmentEntity s : segments) {
                    double score = keywordScore(queryTokens, s.getKeywords());
                    if (score > 0) {
                        keywordScores.put(s.getId(), score);
                    }
                }
            }
        }

        Set<String> candidateIds = new HashSet<>();
        candidateIds.addAll(vectorScores.keySet());
        candidateIds.addAll(keywordScores.keySet());
        if (candidateIds.isEmpty()) {
            return List.of();
        }

        Map<String, SegmentEntity> segById = new HashMap<>();
        for (SegmentEntity s : segmentMapper.selectBatchIds(candidateIds)) {
            segById.put(s.getId(), s);
        }

        List<RetrievedSegment> results = new ArrayList<>();
        for (String id : candidateIds) {
            SegmentEntity s = segById.get(id);
            if (s == null || Boolean.FALSE.equals(s.getEnabled())) {
                continue;
            }
            Map<String, Object> metadata = JsonUtils.parseMap(s.getMetadataJson());
            if (!matchesFilter(metadata, req.getMetadataFilter())) {
                continue;
            }
            double v = vectorScores.getOrDefault(id, 0.0);
            double k = keywordScores.getOrDefault(id, 0.0);
            double fused = switch (method) {
                case VECTOR -> v;
                case FULL_TEXT -> k;
                case HYBRID -> vectorWeight * v + keywordWeight * k;
            };
            Object parent = metadata.get("parentContent");
            results.add(RetrievedSegment.builder()
                    .segmentId(s.getId())
                    .datasetId(s.getDatasetId())
                    .documentId(s.getDocumentId())
                    .position(s.getPosition())
                    .content(s.getContent())
                    .parentContent(parent == null ? null : String.valueOf(parent))
                    .vectorScore(v)
                    .keywordScore(k)
                    .score(fused)
                    .metadata(metadata)
                    .build());
        }

        results.sort(Comparator.comparingDouble(RetrievedSegment::getScore).reversed());

        if (cfg.isRerankEnabled() && !results.isEmpty()) {
            Reranker reranker = pickReranker(cfg);
            int rerankPool = Math.min(results.size(), Math.max(topK, cfg.getRerankPoolSize()));
            results = new ArrayList<>(reranker.rerank(req.getQuery(), results.subList(0, rerankPool), topK));
        }

        List<RetrievedSegment> top = new ArrayList<>();
        for (RetrievedSegment r : results) {
            if (r.getScore() < threshold) {
                continue;
            }
            top.add(r);
            if (top.size() >= topK) {
                break;
            }
        }
        return top;
    }

    private Reranker pickReranker(RetrievalConfig cfg) {
        String name = cfg.getRerankerName();
        // Convention: when a rerankModelId is configured but no explicit name is set,
        // route to the model-based reranker automatically.
        if ((name == null || name.isBlank()) && cfg.getRerankModelId() != null && !cfg.getRerankModelId().isBlank()) {
            name = "model";
        }
        return rerankerRegistry.get(name);
    }

    private static double keywordScore(Set<String> queryTokens, String segmentKeywords) {
        if (segmentKeywords == null || segmentKeywords.isBlank()) {
            return 0.0;
        }
        Set<String> segTokens = new HashSet<>(List.of(segmentKeywords.split(" ")));
        int matched = 0;
        for (String t : queryTokens) {
            if (segTokens.contains(t)) {
                matched++;
            }
        }
        return queryTokens.isEmpty() ? 0.0 : (double) matched / queryTokens.size();
    }

    private static boolean matchesFilter(Map<String, Object> metadata, Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, Object> e : filter.entrySet()) {
            Object actual = metadata.get(e.getKey());
            if (actual == null || !String.valueOf(actual).equals(String.valueOf(e.getValue()))) {
                return false;
            }
        }
        return true;
    }
}
