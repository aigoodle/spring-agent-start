package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.knowledge.retrieve.RetrievalRequest;
import io.github.aigoodle.knowledge.retrieve.RetrievedSegment;
import io.github.aigoodle.knowledge.service.KnowledgeService;
import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.NodeResult;
import io.github.aigoodle.workflow.variable.VariableResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Retrieves relevant chunks from one or more datasets and exposes them as context.
 * Config: {@code datasetIds} (list), {@code query} (template, default {@code sys.query}),
 * {@code topK}. Outputs: {@code result} (joined context text) and {@code segments}.
 */
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
    @SuppressWarnings("unchecked")
    public NodeResult execute(NodeDef node, ExecutionContext ctx) {
        Object dsObj = node.get("datasetIds");
        List<String> datasetIds = new ArrayList<>();
        if (dsObj instanceof List<?> list) {
            list.forEach(o -> datasetIds.add(String.valueOf(o)));
        } else if (dsObj != null) {
            datasetIds.add(String.valueOf(dsObj));
        }
        if (datasetIds.isEmpty()) {
            return NodeResult.failure("Knowledge retrieval requires 'datasetIds'");
        }
        String query = VariableResolver.render(node.getString("query", "{{#sys.query#}}"), ctx.getPool());
        int topK = node.getInt("topK", 5);

        List<RetrievedSegment> segments = knowledgeService.retrieve(datasetIds,
                RetrievalRequest.builder().query(query).topK(topK).build());

        String joined = segments.stream().map(RetrievedSegment::contextText)
                .collect(Collectors.joining("\n\n---\n\n"));
        List<Map<String, Object>> segViews = segments.stream().map(s -> Map.<String, Object>of(
                "content", s.getContent(),
                "score", s.getScore(),
                "documentId", s.getDocumentId() == null ? "" : s.getDocumentId()
        )).toList();

        return NodeResult.empty().output("result", joined).output("segments", segViews);
    }
}
