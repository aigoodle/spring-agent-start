package io.github.aigoodle.knowledge.service;

import io.github.aigoodle.common.exception.PlatformException;
import io.github.aigoodle.knowledge.chunk.ChunkerRegistry;
import io.github.aigoodle.knowledge.index.IndexingService;
import io.github.aigoodle.knowledge.mapper.KnowledgeDocumentMapper;
import io.github.aigoodle.knowledge.reader.DocumentExtractor;
import io.github.aigoodle.knowledge.retrieve.HybridRetriever;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeServiceTenantTest {

    @Test
    void tenantScopedFacadeRejectsForeignDatasetBeforeReadingDocuments() {
        DatasetService datasets = mock(DatasetService.class);
        when(datasets.require("tenant-a", "dataset-b")).thenThrow(
                new PlatformException("dataset_not_found", "Dataset not found", null));
        KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
        KnowledgeService knowledge = new KnowledgeService(
                datasets, documents, mock(ChunkerRegistry.class), mock(IndexingService.class),
                mock(HybridRetriever.class), mock(DocumentExtractor.class));

        assertThatThrownBy(() -> knowledge.listDocuments("tenant-a", "dataset-b"))
                .hasMessageContaining("Dataset not found");
        assertThatThrownBy(() -> knowledge.addText("tenant-a", "dataset-b", "name", "content"))
                .hasMessageContaining("Dataset not found");

        verifyNoInteractions(documents);
    }
}
