package io.github.aigoodle.agent.memory;

import io.github.aigoodle.agent.api.AgentMessage;
import io.github.aigoodle.knowledge.entity.DatasetEntity;
import io.github.aigoodle.knowledge.enums.IndexingTechnique;
import io.github.aigoodle.knowledge.retrieve.RetrievalRequest;
import io.github.aigoodle.knowledge.retrieve.RetrievedSegment;
import io.github.aigoodle.knowledge.service.CreateDatasetRequest;
import io.github.aigoodle.knowledge.service.DatasetService;
import io.github.aigoodle.knowledge.service.KnowledgeService;

import java.util.List;
import java.util.Map;

/**
 * Long-term, semantic memory: every message is embedded into a knowledge dataset and
 * {@link #recall} returns the messages most relevant to the current query (rather than
 * just the most recent). Conversations are isolated by the {@code documentName}
 * metadata. A {@link #fallback} memory provides chronological {@link #load} for the
 * short-term window.
 */
public class VectorAgentMemory implements AgentMemory {

    private final KnowledgeService knowledgeService;
    private final DatasetService datasetService;
    private final String embeddingModelId;
    private final String datasetName;
    private final AgentMemory fallback;

    private volatile String datasetId;

    public VectorAgentMemory(KnowledgeService knowledgeService, DatasetService datasetService,
                             String embeddingModelId, String datasetName, AgentMemory fallback) {
        this.knowledgeService = knowledgeService;
        this.datasetService = datasetService;
        this.embeddingModelId = embeddingModelId;
        this.datasetName = datasetName == null ? "agent-memory" : datasetName;
        this.fallback = fallback;
    }

    @Override
    public void append(String conversationId, String agentId, AgentMessage message) {
        if (conversationId == null || message.content() == null || message.content().isBlank()) {
            return;
        }
        // name = conversationId so retrieval can filter by documentName metadata
        knowledgeService.addText(datasetId(), conversationId, "[" + message.role() + "] " + message.content());
        if (fallback != null) {
            fallback.append(conversationId, agentId, message);
        }
    }

    @Override
    public List<AgentMessage> load(String conversationId, int maxMessages) {
        return fallback != null ? fallback.load(conversationId, maxMessages) : List.of();
    }

    @Override
    public List<AgentMessage> recall(String conversationId, String query, int maxMessages) {
        if (query == null || query.isBlank()) {
            return load(conversationId, maxMessages);
        }
        List<RetrievedSegment> segments = knowledgeService.retrieve(datasetId(),
                RetrievalRequest.builder()
                        .query(query)
                        .topK(maxMessages)
                        .metadataFilter(Map.of("documentName", conversationId))
                        .build());
        return segments.stream().map(s -> parse(s.getContent())).toList();
    }

    private String datasetId() {
        if (datasetId == null) {
            synchronized (this) {
                if (datasetId == null) {
                    DatasetEntity ds = datasetService.create(CreateDatasetRequest.builder()
                            .tenantId("default").name(datasetName)
                            .embeddingModelId(embeddingModelId)
                            .indexingTechnique(IndexingTechnique.HIGH_QUALITY)
                            .build());
                    datasetId = ds.getId();
                }
            }
        }
        return datasetId;
    }

    private static AgentMessage parse(String content) {
        if (content != null && content.startsWith("[")) {
            int end = content.indexOf(']');
            if (end > 0) {
                String role = content.substring(1, end).trim();
                String text = content.substring(end + 1).strip();
                try {
                    return new AgentMessage(AgentMessage.Role.valueOf(role), text);
                } catch (IllegalArgumentException ignored) {
                    // fall through
                }
            }
        }
        return AgentMessage.user(content == null ? "" : content);
    }
}
