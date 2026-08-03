package io.github.aigoodle.web.support;

import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.knowledge.enums.RetrievalMethod;
import io.github.aigoodle.knowledge.retrieve.RetrievalRequest;
import io.github.aigoodle.web.dto.RetrieveRequestDto;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatasetWebSupportTest {

    @Test
    void readsUploadAndUsesFallbackFilename() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "", "text/plain", "knowledge".getBytes(StandardCharsets.UTF_8));

        UploadedDocument upload = UploadedDocument.from(file);

        assertThat(upload.filename()).isEqualTo("upload.bin");
        assertThat(upload.content()).asString(StandardCharsets.UTF_8).isEqualTo("knowledge");
    }

    @Test
    void rejectsEmptyUpload() {
        MockMultipartFile emptyFile = new MockMultipartFile("file", new byte[0]);

        assertThatThrownBy(() -> UploadedDocument.from(emptyFile))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("uploaded file");
    }

    @Test
    void mapsEveryRetrievalOption() {
        RetrieveRequestDto webRequest = new RetrieveRequestDto();
        webRequest.setQuery("human-readable code");
        webRequest.setMethod(RetrievalMethod.HYBRID);
        webRequest.setTopK(8);
        webRequest.setScoreThreshold(0.42);
        webRequest.setVectorWeight(0.7);
        webRequest.setMetadataFilter(Map.of("language", "java"));

        RetrievalRequest request = RetrievalRequestMapper.from(webRequest);

        assertThat(request.getQuery()).isEqualTo("human-readable code");
        assertThat(request.getMethod()).isEqualTo(RetrievalMethod.HYBRID);
        assertThat(request.getTopK()).isEqualTo(8);
        assertThat(request.getScoreThreshold()).isEqualTo(0.42);
        assertThat(request.getVectorWeight()).isEqualTo(0.7);
        assertThat(request.getMetadataFilter()).containsEntry("language", "java");
    }
}
