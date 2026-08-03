package io.github.aigoodle.knowledge.retrieve;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.knowledge.config.RetrievalConfig;
import io.github.aigoodle.knowledge.entity.DatasetEntity;
import io.github.aigoodle.knowledge.entity.SegmentEntity;
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

    public List<RetrievedSegment> retrieve(DatasetEntity dataset,
                                           RetrievalConfig config,
                                           RetrievalRequest request) {
        boolean vectorIndexAvailable = vectorStoreManager.hasVectorIndex(dataset);
        RetrievalPlan plan = RetrievalPlan.resolve(config, request, vectorIndexAvailable);
        Map<String, Double> vectorScores = recallVectorScores(
                dataset, request.getQuery(), plan, vectorIndexAvailable);
        Map<String, Double> keywordScores = recallKeywordScores(dataset, request.getQuery(), plan);

        Set<String> candidateIds = new HashSet<>();
        candidateIds.addAll(vectorScores.keySet());
        candidateIds.addAll(keywordScores.keySet());
        if (candidateIds.isEmpty()) {
            return List.of();
        }

        Map<String, SegmentEntity> segmentsById = new HashMap<>();
        for (SegmentEntity segment : segmentMapper.selectBatchIds(candidateIds)) {
            segmentsById.put(segment.getId(), segment);
        }

        List<RetrievedSegment> results = new ArrayList<>();
        for (String candidateId : candidateIds) {
            SegmentEntity segment = segmentsById.get(candidateId);
            if (segment == null || Boolean.FALSE.equals(segment.getEnabled())) {
                continue;
            }
            Map<String, Object> metadata = JsonUtils.parseMap(segment.getMetadataJson());
            if (!matchesFilter(metadata, request.getMetadataFilter())) {
                continue;
            }
            double vectorScore = vectorScores.getOrDefault(candidateId, 0.0);
            double keywordScore = keywordScores.getOrDefault(candidateId, 0.0);
            Object parentContent = metadata.get("parentContent");
            results.add(RetrievedSegment.builder()
                    .segmentId(segment.getId())
                    .datasetId(segment.getDatasetId())
                    .documentId(segment.getDocumentId())
                    .position(segment.getPosition())
                    .content(segment.getContent())
                    .parentContent(parentContent == null ? null : String.valueOf(parentContent))
                    .vectorScore(vectorScore)
                    .keywordScore(keywordScore)
                    .score(plan.fusedScore(vectorScore, keywordScore))
                    .metadata(metadata)
                    .build());
        }

        results.sort(Comparator.comparingDouble(RetrievedSegment::getScore).reversed());

        if (config.isRerankEnabled() && !results.isEmpty()) {
            Reranker reranker = pickReranker(config);
            int rerankPool = Math.min(results.size(), Math.max(plan.topK(), config.getRerankPoolSize()));
            results = new ArrayList<>(reranker.rerank(
                    request.getQuery(), results.subList(0, rerankPool), plan.topK()));
        }

        List<RetrievedSegment> selectedResults = new ArrayList<>();
        for (RetrievedSegment result : results) {
            if (result.getScore() < plan.scoreThreshold()) {
                continue;
            }
            selectedResults.add(result);
            if (selectedResults.size() >= plan.topK()) {
                break;
            }
        }
        return selectedResults;
    }

    private Map<String, Double> recallVectorScores(DatasetEntity dataset,
                                                   String query,
                                                   RetrievalPlan plan,
                                                   boolean vectorIndexAvailable) {
        Map<String, Double> scores = new HashMap<>();
        if (!plan.usesVectors(vectorIndexAvailable)) {
            return scores;
        }
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(plan.recallLimit())
                .similarityThreshold(0.0)
                .build();
        List<Document> documents = vectorStoreManager.getStore(dataset).similaritySearch(searchRequest);
        if (documents == null) {
            return scores;
        }
        for (Document document : documents) {
            Object segmentId = document.getMetadata().get("segmentId");
            if (segmentId != null) {
                scores.put(segmentId.toString(), document.getScore() == null ? 0.0 : document.getScore());
            }
        }
        return scores;
    }

    private Map<String, Double> recallKeywordScores(DatasetEntity dataset,
                                                    String query,
                                                    RetrievalPlan plan) {
        Map<String, Double> scores = new HashMap<>();
        if (!plan.usesKeywords()) {
            return scores;
        }
        Set<String> queryTokens = new LinkedHashSet<>(KeywordTokenizer.tokenize(query));
        if (queryTokens.isEmpty()) {
            return scores;
        }
        List<SegmentEntity> segments = segmentMapper.selectList(new LambdaQueryWrapper<SegmentEntity>()
                .eq(SegmentEntity::getDatasetId, dataset.getId())
                .eq(SegmentEntity::getEnabled, true));
        for (SegmentEntity segment : segments) {
            double score = keywordScore(queryTokens, segment.getKeywords());
            if (score > 0) {
                scores.put(segment.getId(), score);
            }
        }
        return scores;
    }

    private Reranker pickReranker(RetrievalConfig config) {
        String name = config.getRerankerName();
        // Convention: when a rerankModelId is configured but no explicit name is set,
        // route to the model-based reranker automatically.
        if ((name == null || name.isBlank())
                && config.getRerankModelId() != null
                && !config.getRerankModelId().isBlank()) {
            name = "model";
        }
        return rerankerRegistry.get(name);
    }

    private static double keywordScore(Set<String> queryTokens, String segmentKeywords) {
        if (segmentKeywords == null || segmentKeywords.isBlank()) {
            return 0.0;
        }
        Set<String> segmentTokens = new HashSet<>(List.of(segmentKeywords.split(" ")));
        int matched = 0;
        for (String token : queryTokens) {
            if (segmentTokens.contains(token)) {
                matched++;
            }
        }
        return queryTokens.isEmpty() ? 0.0 : (double) matched / queryTokens.size();
    }

    private static boolean matchesFilter(Map<String, Object> metadata, Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, Object> expectedEntry : filter.entrySet()) {
            Object actual = metadata.get(expectedEntry.getKey());
            if (actual == null
                    || !String.valueOf(actual).equals(String.valueOf(expectedEntry.getValue()))) {
                return false;
            }
        }
        return true;
    }
}
