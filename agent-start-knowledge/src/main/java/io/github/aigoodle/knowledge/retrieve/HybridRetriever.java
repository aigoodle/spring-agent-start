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
    private final List<QueryTransformer> queryTransformers;

    public HybridRetriever(SegmentMapper segmentMapper, VectorStoreManager vectorStoreManager) {
        this(segmentMapper, vectorStoreManager, new RerankerRegistry(List.of(), new NoopReranker()));
    }

    public HybridRetriever(SegmentMapper segmentMapper, VectorStoreManager vectorStoreManager,
                           RerankerRegistry rerankerRegistry) {
        this(segmentMapper, vectorStoreManager, rerankerRegistry, List.of(new DefaultQueryTransformer()));
    }

    public HybridRetriever(SegmentMapper segmentMapper, VectorStoreManager vectorStoreManager,
                           RerankerRegistry rerankerRegistry, List<QueryTransformer> queryTransformers) {
        this.segmentMapper = segmentMapper;
        this.vectorStoreManager = vectorStoreManager;
        this.rerankerRegistry = rerankerRegistry;
        this.queryTransformers = queryTransformers == null ? List.of() : queryTransformers;
    }

    public List<RetrievedSegment> retrieve(DatasetEntity dataset,
                                           RetrievalConfig config,
                                           RetrievalRequest request) {
        boolean vectorIndexAvailable = vectorStoreManager.hasVectorIndex(dataset);
        RetrievalPlan plan = RetrievalPlan.resolve(config, request, vectorIndexAvailable);
        List<String> queries = queryVariants(request.getQuery(), config);
        Map<String, Double> vectorScores = new HashMap<>();
        Map<String, Double> keywordScores = new HashMap<>();
        for (String query : queries) {
            mergeMaximum(vectorScores, recallVectorScores(dataset, query, plan, vectorIndexAvailable));
            mergeMaximum(keywordScores, recallKeywordScores(dataset, query, plan));
        }
        Map<String, Integer> vectorRanks = ranks(vectorScores);
        Map<String, Integer> keywordRanks = ranks(keywordScores);

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
                    .score(plan.fusedScore(vectorScore, keywordScore,
                            vectorRanks.getOrDefault(candidateId, 0),
                            keywordRanks.getOrDefault(candidateId, 0)))
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
        Map<String, Integer> perDocument = new HashMap<>();
        for (RetrievedSegment result : results) {
            if (result.getScore() < plan.scoreThreshold()) {
                continue;
            }
            if (plan.maxChunksPerDocument() > 0
                    && perDocument.getOrDefault(result.getDocumentId(), 0) >= plan.maxChunksPerDocument()) {
                continue;
            }
            selectedResults.add(result);
            perDocument.merge(result.getDocumentId(), 1, Integer::sum);
            if (selectedResults.size() >= plan.topK()) {
                break;
            }
        }
        expandAdjacentContext(selectedResults, config.getNeighborWindow());
        return selectedResults;
    }

    private List<String> queryVariants(String query, RetrievalConfig config) {
        if (!config.isQueryExpansionEnabled()) return List.of(query);
        Set<String> variants = new LinkedHashSet<>();
        variants.add(query);
        String current = query;
        for (QueryTransformer transformer : queryTransformers.stream()
                .sorted(Comparator.comparingInt(QueryTransformer::getOrder)).toList()) {
            for (String candidate : transformer.transform(current)) {
                if (candidate != null && !candidate.isBlank()) variants.add(candidate);
            }
        }
        return variants.stream().limit(Math.max(1, config.getMaxQueryVariants())).toList();
    }

    private static void mergeMaximum(Map<String, Double> target, Map<String, Double> source) {
        source.forEach((id, score) -> target.merge(id, score, Math::max));
    }

    private void expandAdjacentContext(List<RetrievedSegment> results, int window) {
        if (window <= 0) return;
        for (RetrievedSegment result : results) {
            int position = result.getPosition() == null ? 0 : result.getPosition();
            List<SegmentEntity> neighbors = segmentMapper.selectList(new LambdaQueryWrapper<SegmentEntity>()
                    .eq(SegmentEntity::getDocumentId, result.getDocumentId())
                    .eq(SegmentEntity::getEnabled, true)
                    .between(SegmentEntity::getPosition, Math.max(0, position - window), position + window)
                    .orderByAsc(SegmentEntity::getPosition));
            if (neighbors.size() > 1) {
                result.setExpandedContext(neighbors.stream().map(SegmentEntity::getContent)
                        .filter(content -> content != null && !content.isBlank())
                        .reduce((left, right) -> left + "\n\n" + right).orElse(result.contextText()));
            }
        }
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
        Map<String, Integer> documentFrequency = documentFrequency(segments);
        for (SegmentEntity segment : segments) {
            Map<String, Object> metadata = JsonUtils.parseMap(segment.getMetadataJson());
            double score = keywordScore(queryTokens, segment, metadata, documentFrequency, segments.size());
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

    private static double keywordScore(Set<String> queryTokens, SegmentEntity segment,
                                       Map<String, Object> metadata,
                                       Map<String, Integer> documentFrequency, int corpusSize) {
        if (segment.getKeywords() == null || segment.getKeywords().isBlank()) {
            return 0.0;
        }
        Set<String> segmentTokens = new HashSet<>(List.of(segment.getKeywords().split(" ")));
        Set<String> headingTokens = KeywordTokenizer.tokenSet(String.valueOf(metadata.getOrDefault("heading", "")));
        Set<String> titleTokens = KeywordTokenizer.tokenSet(String.valueOf(metadata.getOrDefault("documentName", "")));
        double matched = 0.0;
        double possible = 0.0;
        for (String token : queryTokens) {
            double idf = Math.log(1.0 + (double) (corpusSize + 1)
                    / (documentFrequency.getOrDefault(token, 0) + 1));
            possible += idf;
            double fieldBoost = titleTokens.contains(token) ? 1.5
                    : headingTokens.contains(token) ? 1.3
                    : segmentTokens.contains(token) ? 1.0 : 0.0;
            matched += idf * fieldBoost;
        }
        return possible == 0.0 ? 0.0 : Math.min(1.0, matched / possible);
    }

    private static Map<String, Integer> documentFrequency(List<SegmentEntity> segments) {
        Map<String, Integer> frequencies = new HashMap<>();
        for (SegmentEntity segment : segments) {
            if (segment.getKeywords() == null) continue;
            for (String token : new HashSet<>(List.of(segment.getKeywords().split(" ")))) {
                frequencies.merge(token, 1, Integer::sum);
            }
        }
        return frequencies;
    }

    private static Map<String, Integer> ranks(Map<String, Double> scores) {
        List<Map.Entry<String, Double>> ordered = new ArrayList<>(scores.entrySet());
        ordered.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        Map<String, Integer> ranks = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) ranks.put(ordered.get(i).getKey(), i + 1);
        return ranks;
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
