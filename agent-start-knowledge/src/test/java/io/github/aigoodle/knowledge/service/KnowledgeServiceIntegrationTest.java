package io.github.aigoodle.knowledge.service;

import io.github.aigoodle.knowledge.KnowledgeTestApplication;
import io.github.aigoodle.knowledge.config.ProcessRule;
import io.github.aigoodle.knowledge.config.RetrievalConfig;
import io.github.aigoodle.knowledge.entity.DatasetEntity;
import io.github.aigoodle.knowledge.enums.ChunkingTemplate;
import io.github.aigoodle.knowledge.enums.IndexingTechnique;
import io.github.aigoodle.knowledge.enums.RetrievalMethod;
import io.github.aigoodle.knowledge.retrieve.RetrievalRequest;
import io.github.aigoodle.knowledge.retrieve.RetrievedSegment;
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

@SpringBootTest(classes = KnowledgeTestApplication.class)
class KnowledgeServiceIntegrationTest {

    @Autowired
    private ModelService modelService;
    @Autowired
    private DatasetService datasetService;
    @Autowired
    private KnowledgeService knowledgeService;

    private static final String DOC = String.join("\n",
            "Cats are independent pets that enjoy sleeping all day.",
            "Dogs are loyal animals and love playing fetch outdoors.",
            "Paris is the capital city of France and very beautiful.",
            "Python is a popular programming language for data science.");

    private String registerEmbeddingModel() {
        ModelEntity m = modelService.register(ModelRegistration.builder()
                .tenantId("kb").providerName("fake").modelName("hash-embed")
                .modelType(ModelType.TEXT_EMBEDDING)
                .credentials(Map.of("dimensions", 256)).build());
        return m.getId();
    }

    private DatasetEntity highQualityDataset(ChunkingTemplate template) {
        ProcessRule rule = new ProcessRule();
        rule.setTemplate(template);
        rule.setChunkTokens(12);
        rule.setOverlapTokens(2);
        rule.setParentChunkTokens(40);
        return datasetService.create(CreateDatasetRequest.builder()
                .tenantId("kb").name("kb-" + template)
                .embeddingModelId(registerEmbeddingModel())
                .indexingTechnique(IndexingTechnique.HIGH_QUALITY)
                .processRule(rule)
                .retrievalConfig(RetrievalConfig.hybrid())
                .build());
    }

    @Test
    void ingestAndHybridRetrieve() {
        DatasetEntity ds = highQualityDataset(ChunkingTemplate.NAIVE);
        var doc = knowledgeService.addText(ds.getId(), "animals.txt", DOC);
        assertEquals("COMPLETED", doc.getStatus().name());
        assertTrue(doc.getSegmentCount() > 0);

        List<RetrievedSegment> dogs = knowledgeService.retrieve(ds.getId(), "dogs loyal fetch");
        assertFalse(dogs.isEmpty(), "should retrieve something for 'dogs'");
        assertTrue(dogs.get(0).getContent().toLowerCase().contains("dog"),
                "top hit should be the dogs chunk, was: " + dogs.get(0).getContent());

        List<RetrievedSegment> py = knowledgeService.retrieve(ds.getId(), "python programming language");
        assertTrue(py.get(0).getContent().toLowerCase().contains("python"),
                "top hit should be the python chunk, was: " + py.get(0).getContent());

        // scores carry both signals in hybrid mode
        assertTrue(dogs.get(0).getScore() > 0);
        assertTrue(dogs.get(0).getVectorScore() > 0);
    }

    @Test
    void vectorOnlyAndKeywordOnlyBothWork() {
        DatasetEntity ds = highQualityDataset(ChunkingTemplate.NAIVE);
        knowledgeService.addText(ds.getId(), "animals.txt", DOC);

        var vec = knowledgeService.retrieve(ds.getId(), RetrievalRequest.builder()
                .query("capital of France Paris").method(RetrievalMethod.VECTOR).build());
        assertTrue(vec.get(0).getContent().toLowerCase().contains("paris"));

        var kw = knowledgeService.retrieve(ds.getId(), RetrievalRequest.builder()
                .query("capital of France Paris").method(RetrievalMethod.FULL_TEXT).build());
        assertTrue(kw.get(0).getContent().toLowerCase().contains("paris"));
        assertTrue(kw.get(0).getKeywordScore() > 0);
    }

    @Test
    void economyDatasetUsesKeywordRetrievalWithoutEmbeddings() {
        DatasetEntity ds = datasetService.create(CreateDatasetRequest.builder()
                .tenantId("kb").name("economy")
                .indexingTechnique(IndexingTechnique.ECONOMY)
                .processRule(new ProcessRule())
                .build());
        knowledgeService.addText(ds.getId(), "animals.txt", DOC);

        var res = knowledgeService.retrieve(ds.getId(), "python data science");
        assertFalse(res.isEmpty());
        assertTrue(res.get(0).getContent().toLowerCase().contains("python"));
    }

    @Test
    void parentChildRetrievalReturnsParentContext() {
        DatasetEntity ds = highQualityDataset(ChunkingTemplate.PARENT_CHILD);
        knowledgeService.addText(ds.getId(), "animals.txt", DOC.repeat(2));

        var res = knowledgeService.retrieve(ds.getId(), "dogs loyal fetch");
        assertFalse(res.isEmpty());
        RetrievedSegment top = res.get(0);
        assertNotNull(top.getParentContent(), "parent-child chunks must carry parent context");
        assertTrue(top.contextText().length() >= top.getContent().length());
    }

    @Test
    void metadataFilterRestrictsResults() {
        DatasetEntity ds = highQualityDataset(ChunkingTemplate.NAIVE);
        knowledgeService.addText(ds.getId(), "animals.txt", DOC);
        knowledgeService.addText(ds.getId(), "other.txt", "Dogs also appear in this second document.");

        var filtered = knowledgeService.retrieve(ds.getId(), RetrievalRequest.builder()
                .query("dogs")
                .metadataFilter(Map.of("documentName", "other.txt"))
                .build());
        assertFalse(filtered.isEmpty());
        assertTrue(filtered.stream().allMatch(r -> "other.txt".equals(r.getMetadata().get("documentName"))));
    }

    @Test
    void deleteDocumentRemovesSegments() {
        DatasetEntity ds = highQualityDataset(ChunkingTemplate.NAIVE);
        var doc = knowledgeService.addText(ds.getId(), "animals.txt", DOC);
        assertFalse(knowledgeService.retrieve(ds.getId(), "python").isEmpty());

        knowledgeService.deleteDocument(doc.getId());
        assertTrue(knowledgeService.retrieve(ds.getId(), "python").isEmpty(),
                "after deletion nothing should be retrievable");
    }

    @Test
    void retrievalCanExpandAdjacentChunkContext() {
        DatasetEntity ds = highQualityDataset(ChunkingTemplate.NAIVE);
        RetrievalConfig config = RetrievalConfig.hybrid();
        config.setNeighborWindow(1);
        UpdateDatasetRequest patch = new UpdateDatasetRequest();
        patch.setRetrievalConfig(config);
        datasetService.update(ds.getId(), patch);
        knowledgeService.addText(ds.getId(), "animals.txt", DOC);

        RetrievedSegment hit = knowledgeService.retrieve(ds.getId(), "dogs loyal fetch").get(0);
        assertNotNull(hit.getExpandedContext());
        assertTrue(hit.contextText().length() >= hit.getContent().length());
    }
}
