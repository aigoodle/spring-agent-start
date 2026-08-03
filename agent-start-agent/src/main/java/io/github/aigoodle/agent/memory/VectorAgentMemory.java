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

    private static final String DEFAULT_DATASET_NAME = "agent-memory";

    private final KnowledgeService knowledgeService;
    private final DatasetService datasetService;
    private final String embeddingModelId;
    private final String datasetName;
    private final AgentMemory fallbackMemory;

    private volatile String datasetId;

    public VectorAgentMemory(KnowledgeService knowledgeService, DatasetService datasetService,
                             String embeddingModelId, String datasetName,
                             AgentMemory fallbackMemory) {
        this.knowledgeService = knowledgeService;
        this.datasetService = datasetService;
        this.embeddingModelId = embeddingModelId;
        this.datasetName = datasetName == null || datasetName.isBlank()
                ? DEFAULT_DATASET_NAME
                : datasetName;
        this.fallbackMemory = fallbackMemory;
    }

    @Override
    public void append(String conversationId, String agentId, AgentMessage message) {
        if (conversationId == null || message.content() == null || message.content().isBlank()) {
            return;
        }
        // name = conversationId so retrieval can filter by documentName metadata
        knowledgeService.addText(getOrCreateDatasetId(), conversationId,
                "[" + message.role() + "] " + message.content());
        if (fallbackMemory != null) {
            fallbackMemory.append(conversationId, agentId, message);
        }
    }

    @Override
    public List<AgentMessage> load(String conversationId, int maxMessages) {
        return fallbackMemory != null
                ? fallbackMemory.load(conversationId, maxMessages)
                : List.of();
    }

    @Override
    public List<AgentMessage> recall(String conversationId, String query, int maxMessages) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        if (query == null || query.isBlank()) {
            return load(conversationId, maxMessages);
        }
        int resultLimit = Math.max(1, maxMessages);
        List<RetrievedSegment> matchingSegments = knowledgeService.retrieve(
                getOrCreateDatasetId(),
                RetrievalRequest.builder()
                        .query(query)
                        .topK(resultLimit)
                        .metadataFilter(Map.of("documentName", conversationId))
                        .build());
        return matchingSegments.stream()
                .map(RetrievedSegment::getContent)
                .map(VectorAgentMemory::parseMessage)
                .toList();
    }

    private String getOrCreateDatasetId() {
        if (datasetId == null) {
            synchronized (this) {
                if (datasetId == null) {
                    DatasetEntity memoryDataset = datasetService.create(CreateDatasetRequest.builder()
                            .tenantId("default").name(datasetName)
                            .embeddingModelId(embeddingModelId)
                            .indexingTechnique(IndexingTechnique.HIGH_QUALITY)
                            .build());
                    datasetId = memoryDataset.getId();
                }
            }
        }
        return datasetId;
    }

    private static AgentMessage parseMessage(String content) {
        if (content != null && content.startsWith("[")) {
            int closingBracket = content.indexOf(']');
            if (closingBracket > 0) {
                String roleName = content.substring(1, closingBracket).trim();
                String messageText = content.substring(closingBracket + 1).strip();
                try {
                    return new AgentMessage(AgentMessage.Role.valueOf(roleName), messageText);
                } catch (IllegalArgumentException unknownRole) {
                    // Preserve unrecognized legacy content as a user message.
                }
            }
        }
        return AgentMessage.user(content == null ? "" : content);
    }
}
