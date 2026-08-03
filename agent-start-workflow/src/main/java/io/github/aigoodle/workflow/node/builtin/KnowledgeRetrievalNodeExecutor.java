package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.knowledge.retrieve.RetrievedSegment;
import io.github.aigoodle.knowledge.service.KnowledgeService;
import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.NodeResult;

import java.util.List;

/** Retrieves knowledge segments and exposes joined context plus source details. */
public class KnowledgeRetrievalNodeExecutor implements NodeExecutor {

    private final KnowledgeService knowledgeService;

    public KnowledgeRetrievalNodeExecutor(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @Override
    public NodeType type() {
        return NodeType.KNOWLEDGE_RETRIEVAL;
    }

    @Override
    public NodeResult execute(NodeDef node, ExecutionContext context) {
        KnowledgeRetrievalConfiguration configuration =
                KnowledgeRetrievalConfiguration.from(node, context);
        if (!configuration.hasDatasets()) {
            return NodeResult.failure("Knowledge retrieval requires 'datasetIds'");
        }

        List<RetrievedSegment> segments = knowledgeService.retrieve(
                configuration.datasetIds(), configuration.retrievalRequest());
        return KnowledgeRetrievalResultMapper.map(segments);
    }
}
