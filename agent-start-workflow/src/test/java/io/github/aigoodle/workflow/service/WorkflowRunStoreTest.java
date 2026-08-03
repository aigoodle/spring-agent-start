package io.github.aigoodle.workflow.service;

import io.github.aigoodle.workflow.engine.WorkflowRunResult;
import io.github.aigoodle.workflow.entity.WorkflowRunEntity;
import io.github.aigoodle.workflow.mapper.WorkflowRunMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorkflowRunStoreTest {

    private final WorkflowRunMapper workflowRunMapper = mock(WorkflowRunMapper.class);
    private final WorkflowRunStore runStore = new WorkflowRunStore(workflowRunMapper);

    @Test
    void recordsStoredRunWithItsWorkflowIdentity() {
        WorkflowRunResult result = WorkflowRunResult.forRun("run-1", new ArrayList<>())
                .succeed(Map.of("answer", "Done"));

        runStore.recordStoredRun(
                "workflow-1", "conversation-1", Map.of("question", "Why?"), result);

        WorkflowRunEntity entity = insertedEntity();
        assertThat(entity.getId()).isEqualTo("run-1");
        assertThat(entity.getWorkflowId()).isEqualTo("workflow-1");
        assertThat(entity.getConversationId()).isEqualTo("conversation-1");
        assertThat(entity.getStatus()).isEqualTo("SUCCESS");
        assertThat(entity.getInputsJson()).contains("question", "Why?");
        assertThat(entity.getOutputsJson()).contains("answer", "Done");
    }

    @Test
    void recordsAdHocRunWithoutInventingAWorkflowIdentity() {
        WorkflowRunResult result = WorkflowRunResult.forRun("run-2", new ArrayList<>())
                .fail("Node failed", Map.of());

        runStore.recordAdHocRun(null, Map.of(), result);

        WorkflowRunEntity entity = insertedEntity();
        assertThat(entity.getWorkflowId()).isNull();
        assertThat(entity.getStatus()).isEqualTo("FAILED");
        assertThat(entity.getError()).isEqualTo("Node failed");
    }

    @Test
    void observabilityFailureDoesNotReplaceTheWorkflowOutcome() {
        doThrow(new IllegalStateException("database unavailable"))
                .when(workflowRunMapper).insert(any(WorkflowRunEntity.class));
        WorkflowRunResult result = WorkflowRunResult.forRun("run-3", new ArrayList<>())
                .succeed(Map.of());

        assertThatCode(() -> runStore.recordAdHocRun(null, Map.of(), result))
                .doesNotThrowAnyException();
    }

    private WorkflowRunEntity insertedEntity() {
        ArgumentCaptor<WorkflowRunEntity> entityCaptor = ArgumentCaptor.forClass(WorkflowRunEntity.class);
        verify(workflowRunMapper).insert(entityCaptor.capture());
        return entityCaptor.getValue();
    }
}
