package io.github.aigoodle.knowledge.index;

import io.github.aigoodle.knowledge.KnowledgeTestApplication;
import io.github.aigoodle.knowledge.entity.DatasetEntity;
import io.github.aigoodle.knowledge.enums.IndexVersionStatus;
import io.github.aigoodle.knowledge.enums.IndexingTechnique;
import io.github.aigoodle.knowledge.service.CreateDatasetRequest;
import io.github.aigoodle.knowledge.service.DatasetService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = KnowledgeTestApplication.class)
class IndexVersionServiceTest {
    @Autowired private DatasetService datasetService;
    @Autowired private IndexVersionService versionService;

    @Test
    void atomicallyActivatesAndRollsBackGenerations() {
        DatasetEntity dataset = datasetService.create(CreateDatasetRequest.builder()
                .tenantId("index-version-test").name("versioned")
                .indexingTechnique(IndexingTechnique.ECONOMY).build());
        var v1 = versionService.beginRebuild(dataset.getTenantId(), dataset.getId(),
                "v1", "embed-v1", "chunk-v1", "aaa");
        var v2 = versionService.beginRebuild(dataset.getTenantId(), dataset.getId(),
                "v2", "embed-v2", "chunk-v2", "bbb");

        versionService.activate(dataset.getTenantId(), dataset.getId(), v1.getId());
        versionService.activate(dataset.getTenantId(), dataset.getId(), v2.getId());

        assertThat(versionService.list(dataset.getTenantId(), dataset.getId()))
                .filteredOn(v -> v.getId().equals(v1.getId())).singleElement()
                .extracting(v -> v.getStatus()).isEqualTo(IndexVersionStatus.RETIRED);
        assertThat(versionService.list(dataset.getTenantId(), dataset.getId()))
                .filteredOn(v -> v.getId().equals(v2.getId())).singleElement()
                .extracting(v -> v.getStatus()).isEqualTo(IndexVersionStatus.ACTIVE);

        versionService.activate(dataset.getTenantId(), dataset.getId(), v1.getId());
        assertThat(datasetService.require(dataset.getTenantId(), dataset.getId()).getActiveIndexVersionId())
                .isEqualTo(v1.getId());
    }
}
