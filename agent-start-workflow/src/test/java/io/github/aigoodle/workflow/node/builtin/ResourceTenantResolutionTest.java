package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.common.context.*;
import io.github.aigoodle.model.entity.ModelEntity;
import io.github.aigoodle.model.enums.ModelType;
import io.github.aigoodle.model.service.ModelService;
import io.github.aigoodle.knowledge.service.KnowledgeService;
import io.github.aigoodle.workflow.graph.*;
import io.github.aigoodle.workflow.node.ExecutionContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ResourceTenantResolutionTest {
    @Test void modelAndKnowledgeUseOwnerWhileCallerIdentityIsRestored() {
        var context = ExecutionContext.start(Map.of("resourceTenantId", "attacker"), "c", null);
        context.setTenantId("consumer"); context.setResourceTenantId("owner");
        var models = mock(ModelService.class);
        var entity = new ModelEntity(); entity.setId("m");
        var model = mock(ChatModel.class);
        when(models.findOrMaterialize("owner", "qwen", "model", ModelType.LLM)).thenAnswer(call -> {
            assertThat(UserContextHolder.currentTenantId()).isEqualTo("owner"); return entity;
        });
        when(models.getChatModel("owner", "m")).thenReturn(model);
        var node = NodeDef.of("llm", NodeType.LLM).with("modelProvider", "qwen").with("modelName", "model");
        var knowledge = mock(KnowledgeService.class);
        when(knowledge.retrieve(eq("owner"), eq(List.of("dataset")), any())).thenAnswer(call -> {
            assertThat(UserContextHolder.currentTenantId()).isEqualTo("owner"); return List.of();
        });
        var caller = CurrentUser.builder().tenantId("consumer").userId("user").departmentId("dept").build();
        UserContextHolder.runAs(caller, () -> {
            assertThat(NodeModelResolver.resolveModel(node, context, models)).isSameAs(model);
            new KnowledgeRetrievalNodeExecutor(knowledge).execute(
                    NodeDef.of("kb", NodeType.KNOWLEDGE_RETRIEVAL).with("datasetIds", List.of("dataset")), context);
            assertThat(UserContextHolder.get()).isSameAs(caller);
            assertThat(context.getTenantId()).isEqualTo("consumer");
            assertThatThrownBy(() -> context.withResourceTenant(() -> { throw new IllegalStateException("failed"); }))
                    .hasMessage("failed");
            assertThat(UserContextHolder.get()).isSameAs(caller);
        });
        verify(knowledge).retrieve(eq("owner"), eq(List.of("dataset")), any());
    }

    @Test void clientInputsCannotSelectResourceTenant() {
        var context = ExecutionContext.start(Map.of("resourceTenantId", "owner", "_resourceTenantId", "owner"), "c", null);
        context.setTenantId("consumer");
        assertThat(context.resourceTenant()).isEqualTo("consumer");
    }
}
