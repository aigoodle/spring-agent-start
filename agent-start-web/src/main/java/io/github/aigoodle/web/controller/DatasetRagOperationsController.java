package io.github.aigoodle.web.controller;

import io.github.aigoodle.knowledge.entity.IndexVersionEntity;
import io.github.aigoodle.knowledge.eval.RetrievalEvaluationCase;
import io.github.aigoodle.knowledge.eval.RetrievalEvaluationDataset;
import io.github.aigoodle.knowledge.eval.RetrievalEvaluationReport;
import io.github.aigoodle.knowledge.eval.RetrievalEvaluator;
import io.github.aigoodle.knowledge.index.IndexVersionService;
import io.github.aigoodle.knowledge.retrieve.RetrievalRequest;
import io.github.aigoodle.knowledge.service.KnowledgeService;
import io.github.aigoodle.web.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.aigoodle.common.context.UserContextHolder.currentTenantId;

/** Operational RAG APIs consumed by the embeddable knowledge-hub component. */
@RestController
@ConditionalOnBean(KnowledgeService.class)
@RequestMapping("/datasets/{datasetId}/rag")
public class DatasetRagOperationsController {
    private final KnowledgeService knowledgeService;
    private final IndexVersionService versions;
    private final RetrievalEvaluator evaluator;

    public DatasetRagOperationsController(KnowledgeService knowledgeService,
                                          IndexVersionService versions,
                                          RetrievalEvaluator evaluator) {
        this.knowledgeService = knowledgeService;
        this.versions = versions;
        this.evaluator = evaluator;
    }

    @GetMapping("/index-versions")
    public ApiResponse<List<IndexVersionEntity>> listVersions(@PathVariable String datasetId) {
        knowledgeService.requireDataset(currentTenantId(), datasetId);
        return ApiResponse.ok(versions.list(currentTenantId(), datasetId));
    }

    @PostMapping("/index-versions")
    public ApiResponse<IndexVersionEntity> beginVersion(@PathVariable String datasetId,
                                                        @RequestBody BeginVersionRequest request) {
        knowledgeService.requireDataset(currentTenantId(), datasetId);
        return ApiResponse.ok(versions.beginRebuild(currentTenantId(), datasetId, request.version(),
                request.embeddingModelVersion(), request.chunkingRuleVersion(), request.contentChecksum()));
    }

    @PostMapping("/index-versions/{versionId}/activate")
    public ApiResponse<Void> activate(@PathVariable String datasetId, @PathVariable String versionId) {
        versions.activate(currentTenantId(), datasetId, versionId);
        return ApiResponse.ok();
    }

    @PostMapping("/evaluate")
    public ApiResponse<RetrievalEvaluationReport> evaluate(@PathVariable String datasetId,
                                                            @RequestBody EvaluationRequest request) {
        knowledgeService.requireDataset(currentTenantId(), datasetId);
        int topK = request.topK() == null ? 5 : request.topK();
        List<RetrievalEvaluationCase> cases = request.cases() == null ? List.of() : request.cases().stream()
                .map(c -> new RetrievalEvaluationCase(c.id(), c.query(),
                        c.relevantSegmentIds() == null ? Set.of() : c.relevantSegmentIds(),
                        c.noAnswerExpected(), c.citedSegmentIds())).toList();
        RetrievalEvaluationDataset dataset = new RetrievalEvaluationDataset(
                request.name(), request.version(), cases);
        RetrievalEvaluationReport report = evaluator.evaluate(dataset, request.experimentName(), topK,
                request.configuration(), query -> knowledgeService.retrieve(currentTenantId(), datasetId,
                                RetrievalRequest.builder().query(query).topK(topK).build()).stream()
                        .map(hit -> hit.getSegmentId()).toList());
        return ApiResponse.ok(report);
    }

    public record BeginVersionRequest(String version, String embeddingModelVersion,
                                      String chunkingRuleVersion, String contentChecksum) {}
    public record EvaluationCaseRequest(String id, String query, Set<String> relevantSegmentIds,
                                        boolean noAnswerExpected, List<String> citedSegmentIds) {}
    public record EvaluationRequest(String name, String version, String experimentName, Integer topK,
                                    List<EvaluationCaseRequest> cases, Map<String, String> configuration) {}
}
