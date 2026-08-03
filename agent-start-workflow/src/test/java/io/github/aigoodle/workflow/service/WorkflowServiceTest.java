package io.github.aigoodle.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aigoodle.workflow.engine.WorkflowEngine;
import io.github.aigoodle.workflow.entity.WorkflowEntity;
import io.github.aigoodle.workflow.mapper.WorkflowMapper;
import io.github.aigoodle.workflow.mapper.WorkflowRunMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowServiceTest {

    private final WorkflowMapper workflowMapper = mock(WorkflowMapper.class);
    private final WorkflowService workflowService = new WorkflowService(
            workflowMapper, mock(WorkflowRunMapper.class), mock(WorkflowEngine.class));

    @Test
    void createsDraftFromNamedDefinitionWithoutPositionalAmbiguity() {
        WorkflowDraftDefinition definition = new WorkflowDraftDefinition(
                "app-1",
                "tenant-1",
                "Customer support flow",
                "chatflow",
                null);

        WorkflowEntity draft = workflowService.createDraft(definition);

        assertThat(draft.getId()).isEqualTo("app-1");
        assertThat(draft.getAppId()).isEqualTo("app-1");
        assertThat(draft.getTenantId()).isEqualTo("tenant-1");
        assertThat(draft.getName()).isEqualTo("Customer support flow");
        assertThat(draft.getMode()).isEqualTo("chatflow");
        assertThat(draft.getGraph()).isNotNull();
        verify(workflowMapper).insert(draft);
    }

    @Test
    void updatesOnlyDesignerStateExplicitlyIncludedInTheChangeSet() {
        WorkflowEntity draft = new WorkflowEntity();
        draft.setId("app-1");
        draft.setFeatures("old-features");
        draft.setEnvironmentVariables("old-environment");
        draft.setConversationVariables("old-conversation");
        when(workflowMapper.selectById("app-1")).thenReturn(draft);

        WorkflowDraftChanges changes = new WorkflowDraftChanges(
                new ObjectMapper().createObjectNode(),
                "new-features",
                null,
                "new-conversation");

        WorkflowEntity saved = workflowService.saveDraft("app-1", changes);

        assertThat(saved.getFeatures()).isEqualTo("new-features");
        assertThat(saved.getEnvironmentVariables()).isEqualTo("old-environment");
        assertThat(saved.getConversationVariables()).isEqualTo("new-conversation");
        verify(workflowMapper).updateById(draft);
    }
}
