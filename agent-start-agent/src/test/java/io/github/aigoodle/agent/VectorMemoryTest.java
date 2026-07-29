package io.github.aigoodle.agent;

import io.github.aigoodle.agent.api.AgentMessage;
import io.github.aigoodle.agent.mapper.AgentMessageMapper;
import io.github.aigoodle.agent.memory.JdbcAgentMemory;
import io.github.aigoodle.agent.memory.VectorAgentMemory;
import io.github.aigoodle.knowledge.service.DatasetService;
import io.github.aigoodle.knowledge.service.KnowledgeService;
import io.github.aigoodle.model.entity.ModelEntity;
import io.github.aigoodle.model.enums.ModelType;
import io.github.aigoodle.model.service.ModelRegistration;
import io.github.aigoodle.model.service.ModelService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Semantic long-term memory: messages are embedded and recalled by relevance to the
 * current query, with conversations isolated from one another.
 */
@SpringBootTest(classes = AgentTestApplication.class)
class VectorMemoryTest {

    @Autowired
    private ModelService modelService;
    @Autowired
    private KnowledgeService knowledgeService;
    @Autowired
    private DatasetService datasetService;
    @Autowired
    private AgentMessageMapper messageMapper;

    private VectorAgentMemory memory() {
        ModelEntity emb = modelService.register(ModelRegistration.builder()
                .tenantId("default").providerName("fake").modelName("hash-embed")
                .modelType(ModelType.TEXT_EMBEDDING).credentials(Map.of("dimensions", 256)).build());
        return new VectorAgentMemory(knowledgeService, datasetService, emb.getId(),
                "mem-" + System.nanoTime(), new JdbcAgentMemory(messageMapper));
    }

    @Test
    void recallsBySemanticRelevance() {
        VectorAgentMemory mem = memory();
        mem.append("c1", AgentMessage.assistant("The user loves dogs and playing fetch outdoors"));
        mem.append("c1", AgentMessage.assistant("Paris is the capital city of France"));
        mem.append("c1", AgentMessage.user("Python is a great language for data science"));

        List<AgentMessage> recalled = mem.recall("c1", "tell me about dogs and fetch", 1);
        assertFalse(recalled.isEmpty());
        assertTrue(recalled.get(0).content().toLowerCase().contains("dog"),
                "most relevant memory should be about dogs, was: " + recalled.get(0).content());
    }

    @Test
    void conversationsAreIsolated() {
        VectorAgentMemory mem = memory();
        mem.append("conv-a", AgentMessage.assistant("Cats are independent and sleep a lot"));
        mem.append("conv-b", AgentMessage.assistant("Dogs are loyal and love fetch"));

        List<AgentMessage> fromB = mem.recall("conv-b", "pets", 5);
        assertFalse(fromB.isEmpty());
        assertTrue(fromB.stream().noneMatch(m -> m.content().contains("Cats are independent")),
                "conv-b recall must not leak conv-a memory");
    }
}
