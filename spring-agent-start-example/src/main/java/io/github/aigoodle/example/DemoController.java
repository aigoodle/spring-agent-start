package io.github.aigoodle.example;

import io.github.aigoodle.knowledge.entity.DatasetEntity;
import io.github.aigoodle.knowledge.enums.IndexingTechnique;
import io.github.aigoodle.knowledge.service.CreateDatasetRequest;
import io.github.aigoodle.knowledge.service.DatasetService;
import io.github.aigoodle.knowledge.service.KnowledgeService;
import io.github.aigoodle.model.entity.ModelEntity;
import io.github.aigoodle.model.enums.ModelType;
import io.github.aigoodle.model.service.ModelRegistration;
import io.github.aigoodle.model.service.ModelService;
import io.github.aigoodle.workflow.engine.WorkflowRunResult;
import io.github.aigoodle.workflow.graph.EdgeDef;
import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.graph.WorkflowGraph;
import io.github.aigoodle.workflow.service.WorkflowService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Minimal demo endpoints showing the three modules cooperating:
 * register a model, build a knowledge base, and run a RAG workflow over it.
 */
@RestController
public class DemoController {

    private final ModelService modelService;
    private final DatasetService datasetService;
    private final KnowledgeService knowledgeService;
    private final WorkflowService workflowService;

    public DemoController(ModelService modelService, DatasetService datasetService,
                          KnowledgeService knowledgeService, WorkflowService workflowService) {
        this.modelService = modelService;
        this.datasetService = datasetService;
        this.knowledgeService = knowledgeService;
        this.workflowService = workflowService;
    }

    /** Register an Ollama (or OpenAI-compatible) model. */
    @PostMapping("/models")
    public String registerModel(@RequestParam String provider, @RequestParam String name,
                                @RequestParam ModelType type,
                                @RequestParam(required = false) String apiKey,
                                @RequestParam(required = false) String baseUrl) {
        ModelEntity m = modelService.register(ModelRegistration.builder()
                .providerName(provider).modelName(name).modelType(type)
                .credentials(baseUrl != null ? Map.of("baseUrl", baseUrl)
                        : (apiKey != null ? Map.of("apiKey", apiKey) : Map.of()))
                .build());
        return m.getId();
    }

    /** Create a dataset and ingest a piece of text. */
    @PostMapping("/datasets")
    public String createDataset(@RequestParam String name, @RequestParam String embeddingModelId,
                                @RequestParam String text) {
        DatasetEntity ds = datasetService.create(CreateDatasetRequest.builder()
                .name(name).embeddingModelId(embeddingModelId)
                .indexingTechnique(IndexingTechnique.HIGH_QUALITY).build());
        knowledgeService.addText(ds.getId(), name, text);
        return ds.getId();
    }

    /** Run a RAG workflow: retrieve from the dataset, then answer with the LLM. */
    @PostMapping("/ask")
    public Object ask(@RequestParam String datasetId, @RequestParam String llmModelId,
                      @RequestParam String question) {
        WorkflowGraph g = new WorkflowGraph();
        g.addNode(NodeDef.of("start", NodeType.START));
        g.addNode(NodeDef.of("kb", NodeType.KNOWLEDGE_RETRIEVAL)
                .with("datasetIds", List.of(datasetId)).with("query", "{{#sys.query#}}").with("topK", 3));
        g.addNode(NodeDef.of("llm", NodeType.LLM).with("modelId", llmModelId)
                .with("systemPrompt", "Answer the question using only the provided context.")
                .with("userPrompt", "Context:\n{{#kb.result#}}\n\nQuestion: {{#sys.query#}}"));
        g.addNode(NodeDef.of("end", NodeType.END).with("outputs", Map.of("answer", "{{#llm.text#}}")));
        g.addEdge(EdgeDef.of("start", "kb"));
        g.addEdge(EdgeDef.of("kb", "llm"));
        g.addEdge(EdgeDef.of("llm", "end"));

        WorkflowRunResult r = workflowService.runGraph(g, Map.of("query", question), null);
        return Map.of("success", r.isSuccess(), "answer", r.text(), "error", r.getError() == null ? "" : r.getError());
    }
}
