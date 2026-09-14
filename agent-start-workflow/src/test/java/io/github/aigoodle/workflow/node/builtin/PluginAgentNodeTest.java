package io.github.aigoodle.workflow.node.builtin;

import io.github.aigoodle.agent.api.AgentResponse;
import io.github.aigoodle.agent.runtime.AgentRuntime;
import io.github.aigoodle.common.context.UserContextHolder;
import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.node.ExecutionContext;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class PluginAgentNodeTest {
    @Test void externalRuntimeDoesNotRequireNativeModelAndReceivesWorkflowIdentity() {
        AgentRuntime runtime = (definition, request, steps, tokens) -> {
            assertThat(definition.getRuntimeType()).isEqualTo("PLUGIN");
            assertThat(definition.getRuntimeRef()).isEqualTo("video/plan");
            assertThat(UserContextHolder.currentTenantId()).isEqualTo("tenant-a");
            assertThat(UserContextHolder.currentUserId()).isEqualTo("user-a");
            return AgentResponse.forConversation(request.getConversationId()).complete("Script");
        };
        var context = ExecutionContext.start(Map.of("query", "Write"), "c1", null);
        context.setTenantId("tenant-a"); context.setUserId("user-a");
        var result = new AgentNodeExecutor(runtime).execute(NodeDef.of("agent", NodeType.AGENT)
                .with("runtimeType", "PLUGIN").with("runtimeRef", "video/plan"), context);
        assertThat(result.isFailed()).isFalse();
        assertThat(result.getOutputs()).containsEntry("text", "Script");
        assertThat(UserContextHolder.get()).isNull();
    }
}
