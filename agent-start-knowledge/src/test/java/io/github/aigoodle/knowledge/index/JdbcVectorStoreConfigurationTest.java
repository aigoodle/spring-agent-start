package io.github.aigoodle.knowledge.index;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class JdbcVectorStoreConfigurationTest {

    @Test
    void acceptsSimpleAndSchemaQualifiedTableNames() {
        assertThat(new JdbcVectorStoreConfiguration("dataset-1", "embeddings").tableName())
                .isEqualTo("embeddings");
        assertThat(new JdbcVectorStoreConfiguration("dataset-1", "agent.embeddings").tableName())
                .isEqualTo("agent.embeddings");
    }

    @Test
    void rejectsTableNamesThatCouldChangeTheSqlStatement() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JdbcVectorStoreConfiguration(
                        "dataset-1", "embeddings; DROP TABLE dataset"))
                .withMessageContaining("Invalid vector table name");
    }

    @Test
    void requiresADatasetScope() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JdbcVectorStoreConfiguration(" ", "embeddings"))
                .withMessage("datasetId must not be blank");
    }
}
