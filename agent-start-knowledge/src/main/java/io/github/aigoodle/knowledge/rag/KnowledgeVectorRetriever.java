package io.github.aigoodle.knowledge.rag;

import io.github.aigoodle.knowledge.config.RetrievalConfig;
import io.github.aigoodle.knowledge.entity.DatasetEntity;
import io.github.aigoodle.knowledge.retrieve.HybridRetriever;
import io.github.aigoodle.knowledge.retrieve.RetrievalRequest;
import io.github.aigoodle.knowledge.service.DatasetService;
import io.github.aigoodle.rag.api.PlannedQuery;
import io.github.aigoodle.rag.api.RagPolicy;
import io.github.aigoodle.rag.api.RagRequest;
import io.github.aigoodle.rag.api.RetrievalCandidate;
import io.github.aigoodle.rag.spi.Retriever;
import java.util.ArrayList;
import java.util.List;

/** Adapts the existing dense index to the common RAG retrieval SPI. */
public final class KnowledgeVectorRetriever implements Retriever {
    private final DatasetService datasetService;
    private final HybridRetriever retriever;

    public KnowledgeVectorRetriever(DatasetService datasetService, HybridRetriever retriever) {
        this.datasetService = datasetService;
        this.retriever = retriever;
    }
    @Override public String getName() { return "knowledge-vector"; }
    @Override public RagPolicy.Channel getChannel() { return RagPolicy.Channel.VECTOR; }

    @Override
    public List<RetrievalCandidate> retrieve(PlannedQuery query, RagRequest request) {
        List<RetrievalCandidate> candidates = new ArrayList<>();
        int limit = Math.max(1, request.policy().retrieval().candidateLimitPerQuery());
        for (String datasetId : request.datasetIds()) {
            DatasetEntity dataset = datasetService.require(request.tenantId(), datasetId);
            RetrievalConfig config = datasetService.retrievalConfig(dataset);
            config.setTopK(limit);
            config.setRecallMultiplier(1);
            config.setRerankEnabled(false);
            config.setQueryExpansionEnabled(false);
            retriever.retrieve(dataset, config, RetrievalRequest.builder()
                    .query(query.query()).metadataFilter(request.metadataFilter()).topK(limit).build())
                    .forEach(hit -> candidates.add(new RetrievalCandidate(hit.getDatasetId(), hit.getDocumentId(),
                            hit.getSegmentId(), hit.getPosition(), hit.getContent(), hit.contextText(), hit.getScore(),
                            getChannel(), query.id(), hit.getMetadata())));
        }
        return candidates;
    }
}
