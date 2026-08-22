package io.github.aigoodle.web.controller;

import io.github.aigoodle.knowledge.async.DocumentIngestQueueEntity;
import io.github.aigoodle.knowledge.async.IngestionJobCoordinator;
import io.github.aigoodle.knowledge.async.IngestionRecoveryService;
import io.github.aigoodle.web.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static io.github.aigoodle.common.context.UserContextHolder.currentTenantId;

@RestController
@ConditionalOnBean(IngestionRecoveryService.class)
@RequestMapping("/datasets/{datasetId}/ingestion-jobs")
public class IngestionRecoveryController {
    private final IngestionJobCoordinator coordinator;
    private final IngestionRecoveryService recovery;

    public IngestionRecoveryController(IngestionJobCoordinator coordinator,
                                       IngestionRecoveryService recovery) {
        this.coordinator = coordinator;
        this.recovery = recovery;
    }

    @GetMapping("/poisoned")
    public ApiResponse<List<DocumentIngestQueueEntity>> poisoned(@PathVariable String datasetId) {
        return ApiResponse.ok(coordinator.listPoisoned(currentTenantId(), datasetId));
    }

    @PostMapping("/{documentId}/replay")
    public ApiResponse<Boolean> replay(@PathVariable String datasetId, @PathVariable String documentId) {
        DocumentIngestQueueEntity job = coordinator.find(currentTenantId(), documentId);
        if (job == null || !datasetId.equals(job.getDatasetId())) return ApiResponse.ok(false);
        return ApiResponse.ok(recovery.replay(currentTenantId(), documentId));
    }
}
