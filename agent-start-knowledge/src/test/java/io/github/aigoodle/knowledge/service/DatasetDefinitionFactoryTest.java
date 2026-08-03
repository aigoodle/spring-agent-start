package io.github.aigoodle.knowledge.service;

import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.knowledge.config.ProcessRule;
import io.github.aigoodle.knowledge.config.RetrievalConfig;
import io.github.aigoodle.knowledge.entity.DatasetEntity;
import io.github.aigoodle.knowledge.enums.IndexingTechnique;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatasetDefinitionFactoryTest {

    @Test
    void resolvesDefaultsBeforeValidatingTheDataset() {
        CreateDatasetRequest request = CreateDatasetRequest.builder()
                .name("Product handbook")
                .indexingTechnique(null)
                .build();

        assertThatThrownBy(() -> DatasetDefinitionFactory.create(request))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("embedding model id");
    }

    @Test
    void createsAnEconomyDatasetWithoutAnEmbeddingModel() {
        CreateDatasetRequest request = CreateDatasetRequest.builder()
                .tenantId(" ")
                .name("FAQ")
                .indexingTechnique(IndexingTechnique.ECONOMY)
                .build();

        DatasetEntity dataset = DatasetDefinitionFactory.create(request);

        assertThat(dataset.getTenantId()).isEqualTo("default");
        assertThat(dataset.getIndexingTechnique()).isEqualTo(IndexingTechnique.ECONOMY);
        assertThat(dataset.getDocumentCount()).isZero();
        assertThat(dataset.getSegmentCount()).isZero();
        assertThat(dataset.getProcessRuleJson()).isEqualTo(JsonUtils.toJson(ProcessRule.naive()));
        assertThat(dataset.getRetrievalConfigJson())
                .isEqualTo(JsonUtils.toJson(RetrievalConfig.hybrid()));
    }
}
