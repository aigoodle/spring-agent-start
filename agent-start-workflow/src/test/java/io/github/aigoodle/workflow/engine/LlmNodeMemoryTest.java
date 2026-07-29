package io.github.aigoodle.workflow.engine;

import io.github.aigoodle.model.service.ModelService;
import io.github.aigoodle.workflow.graph.EdgeDef;
import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.graph.WorkflowGraph;
import io.github.aigoodle.workflow.memory.WorkflowConversationMemory;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.builtin.EndNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.LlmNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.StartNodeExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that when the designer's LLM node card enables the memory window
 * (({@code memory.window.enabled = true})) the executor loads prior turns from
 * the injected {@link WorkflowConversationMemory} and interleaves them between
 * the SYSTEM prompt and the fresh USER prompt — Dify parity.
 */
class LlmNodeMemoryTest {

    @Test
    void memoryWindowInjectsPriorTurns() {
        // Capture the full message list the ChatClient sees so we can assert
        // history landed in the right slot.
        AtomicReference<List<Message>> captured = new AtomicReference<>();
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        when(client.prompt()).thenReturn(spec);
        when(spec.messages(anyList())).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return spec;
        });
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(spec.call()).thenReturn(call);
        when(call.content()).thenReturn("assistant reply");

        ModelService modelService = mock(ModelService.class);
        when(modelService.getChatClient("m1")).thenReturn(client);

        // A tiny in-memory implementation of the SPI — no agent-module dependency.
        WorkflowConversationMemory memory = (conversationId, max) -> {
            assertEquals("conv-1", conversationId, "conversationId must be threaded to memory");
            return List.of(
                    new WorkflowConversationMemory.ConversationTurn("user", "who won the 2018 world cup?"),
                    new WorkflowConversationMemory.ConversationTurn("assistant", "France."),
                    new WorkflowConversationMemory.ConversationTurn("user", "and 2022?"),
                    new WorkflowConversationMemory.ConversationTurn("assistant", "Argentina."));
        };

        LlmNodeExecutor llm = new LlmNodeExecutor(modelService, null, memory);
        List<NodeExecutor> execs = List.of(new StartNodeExecutor(), new EndNodeExecutor(), llm);
        WorkflowEngine engine = new WorkflowEngine(new NodeExecutorRegistry(execs));

        WorkflowGraph g = new WorkflowGraph();
        g.addNode(NodeDef.of("start", NodeType.START));
        g.addNode(NodeDef.of("llm", NodeType.LLM)
                .with("modelId", "m1")
                .with("systemPrompt", "You are a helpful assistant.")
                .with("userPrompt", "Who won in 2010?")
                // Designer-shape memory config — mirrors what LLMNodeCard.vue saves.
                .with("memory", Map.of("window", Map.of("enabled", true, "size", 10))));
        g.addNode(NodeDef.of("end", NodeType.END).with("outputs", Map.of("out", "{{#llm.text#}}")));
        g.addEdge(EdgeDef.of("start", "llm"));
        g.addEdge(EdgeDef.of("llm", "end"));

        WorkflowRunResult r = engine.run(g, Map.of(), "conv-1");
        assertTrue(r.isSuccess(), r.getError());
        assertEquals("assistant reply", r.output("out"));

        List<Message> msgs = captured.get();
        assertNotNull(msgs, "ChatClient must have been called with a message list");
        // system + 4 memory turns + 1 fresh user = 6
        assertEquals(6, msgs.size(), () -> "unexpected message count: " + msgs);
        assertTrue(msgs.get(0) instanceof SystemMessage);
        assertTrue(msgs.get(1) instanceof UserMessage);
        assertEquals("who won the 2018 world cup?", ((UserMessage) msgs.get(1)).getText());
        assertTrue(msgs.get(2) instanceof AssistantMessage);
        assertEquals("France.", ((AssistantMessage) msgs.get(2)).getText());
        assertTrue(msgs.get(3) instanceof UserMessage);
        assertTrue(msgs.get(4) instanceof AssistantMessage);
        // The freshly-rendered user prompt must be last so the model treats
        // history as context, not the current query.
        assertTrue(msgs.get(5) instanceof UserMessage);
        assertEquals("Who won in 2010?", ((UserMessage) msgs.get(5)).getText());
    }

    @Test
    void memoryDisabledSkipsHistoryLoad() {
        AtomicReference<List<Message>> captured = new AtomicReference<>();
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        when(client.prompt()).thenReturn(spec);
        when(spec.messages(anyList())).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return spec;
        });
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(spec.call()).thenReturn(call);
        when(call.content()).thenReturn("ok");

        ModelService modelService = mock(ModelService.class);
        when(modelService.getChatClient("m1")).thenReturn(client);

        // If the executor asks memory anyway, this throws — the assertion is
        // therefore "we never got here" via a normal successful run.
        WorkflowConversationMemory memory = (conversationId, max) -> {
            throw new AssertionError("memory should not be consulted when window.enabled=false");
        };

        LlmNodeExecutor llm = new LlmNodeExecutor(modelService, null, memory);
        WorkflowEngine engine = new WorkflowEngine(new NodeExecutorRegistry(
                List.of(new StartNodeExecutor(), new EndNodeExecutor(), llm)));

        WorkflowGraph g = new WorkflowGraph();
        g.addNode(NodeDef.of("start", NodeType.START));
        g.addNode(NodeDef.of("llm", NodeType.LLM)
                .with("modelId", "m1")
                .with("userPrompt", "hello")
                .with("memory", Map.of("window", Map.of("enabled", false, "size", 10))));
        g.addNode(NodeDef.of("end", NodeType.END).with("outputs", Map.of("out", "{{#llm.text#}}")));
        g.addEdge(EdgeDef.of("start", "llm"));
        g.addEdge(EdgeDef.of("llm", "end"));

        WorkflowRunResult r = engine.run(g, Map.of(), "conv-2");
        assertTrue(r.isSuccess(), r.getError());
        assertEquals(1, captured.get().size(), "only the fresh user prompt should be sent");
        assertTrue(captured.get().get(0) instanceof UserMessage);
    }

    @Test
    void memoryWithoutConversationIdIsSkipped() {
        AtomicReference<List<Message>> captured = new AtomicReference<>();
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        when(client.prompt()).thenReturn(spec);
        when(spec.messages(anyList())).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return spec;
        });
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(spec.call()).thenReturn(call);
        when(call.content()).thenReturn("ok");

        ModelService modelService = mock(ModelService.class);
        when(modelService.getChatClient("m1")).thenReturn(client);

        WorkflowConversationMemory memory = (conversationId, max) -> {
            throw new AssertionError("no conversation id → no memory load");
        };

        LlmNodeExecutor llm = new LlmNodeExecutor(modelService, null, memory);
        WorkflowEngine engine = new WorkflowEngine(new NodeExecutorRegistry(
                List.of(new StartNodeExecutor(), new EndNodeExecutor(), llm)));

        WorkflowGraph g = new WorkflowGraph();
        g.addNode(NodeDef.of("start", NodeType.START));
        g.addNode(NodeDef.of("llm", NodeType.LLM)
                .with("modelId", "m1")
                .with("userPrompt", "hi")
                .with("memory", Map.of("window", Map.of("enabled", true, "size", 5))));
        g.addNode(NodeDef.of("end", NodeType.END));
        g.addEdge(EdgeDef.of("start", "llm"));
        g.addEdge(EdgeDef.of("llm", "end"));

        WorkflowRunResult r = engine.run(g, Map.of(), null);
        assertTrue(r.isSuccess(), r.getError());
        assertEquals(1, captured.get().size(),
                "conversationId=null must skip memory even when the window is enabled");
    }
}
