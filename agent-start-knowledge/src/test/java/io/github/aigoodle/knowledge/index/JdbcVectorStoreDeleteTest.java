package io.github.aigoodle.knowledge.index;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class JdbcVectorStoreDeleteTest {

    @Test
    void scopesDeletionToTheConfiguredDataset() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcVectorStore vectorStore = new JdbcVectorStore(
                jdbcTemplate,
                mock(EmbeddingModel.class),
                new JdbcVectorStoreConfiguration("dataset-1", "embeddings"));

        vectorStore.delete(List.of("document-1", "document-2"));

        verify(jdbcTemplate).update(
                "DELETE FROM embeddings WHERE dataset_id = ? AND id IN (?,?)",
                "dataset-1", "document-1", "document-2");
    }
}
