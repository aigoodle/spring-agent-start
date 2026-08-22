package io.github.aigoodle.workflow.service;

import io.github.aigoodle.common.exception.PlatformException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;

/** Polls durable waits; work is coordinated through checkpoint CAS and run leases. */
@Slf4j
public class WorkflowWaitRecoveryService {
    private final WorkflowCheckpointStore store;
    private final PersistentWorkflowRunner runner;

    public WorkflowWaitRecoveryService(WorkflowCheckpointStore store, PersistentWorkflowRunner runner) {
        this.store = store;
        this.runner = runner;
    }

    @Scheduled(fixedDelayString = "${goodle.workflow.wait-recovery-delay-ms:1000}")
    public void recoverDueWaits() {
        LocalDateTime now = LocalDateTime.now();
        store.cancelling(100).forEach(checkpoint -> {
            boolean delivered = runner.cancelLocal(
                    checkpoint.getTenantId(), checkpoint.getRunId(), checkpoint.getInterruptReason());
            if (!delivered && (checkpoint.getLeaseExpiresAt() == null
                    || checkpoint.getLeaseExpiresAt().isBefore(now))) {
                store.finalizeAbandonedCancellation(checkpoint);
            }
        });
        store.pausing(100).forEach(checkpoint -> {
            boolean delivered = runner.pauseLocal(
                    checkpoint.getTenantId(), checkpoint.getRunId(), checkpoint.getInterruptReason());
            if (!delivered && (checkpoint.getLeaseExpiresAt() == null
                    || checkpoint.getLeaseExpiresAt().isBefore(now))) {
                store.finalizeAbandonedPause(checkpoint);
            }
        });
        store.expiredWaits(now, 100).forEach(checkpoint -> {
            try {
                runner.timeoutWait(checkpoint);
            } catch (RuntimeException exception) {
                log.warn("Could not timeout workflow wait {}: {}", checkpoint.getRunId(), exception.getMessage());
            }
        });
        store.dueSleeps(now, 100).forEach(checkpoint -> {
            try {
                runner.wakeSleep(checkpoint);
            } catch (PlatformException exception) {
                if (!"run_lease_conflict".equals(exception.getCode())) {
                    log.warn("Could not wake workflow run {}: {}", checkpoint.getRunId(), exception.getMessage());
                }
            } catch (RuntimeException exception) {
                log.warn("Could not wake workflow run {}: {}", checkpoint.getRunId(), exception.getMessage());
            }
        });
    }
}
