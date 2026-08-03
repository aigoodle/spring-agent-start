package io.github.aigoodle.agent.service;

import io.github.aigoodle.agent.entity.AppAnnotationEntity;
import io.github.aigoodle.agent.mapper.AppAnnotationMapper;
import io.github.aigoodle.common.exception.AgentException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppAnnotationServiceTest {

    @Test
    void createsAnnotationWithReadableDefaults() {
        AppAnnotationMapper annotationMapper = mock(AppAnnotationMapper.class);
        AppAnnotationService annotationService = new AppAnnotationService(annotationMapper);
        AppAnnotationEntity annotation = new AppAnnotationEntity();

        AppAnnotationEntity created = annotationService.create(annotation);

        assertThat(created).isSameAs(annotation);
        assertThat(created.getHitCount()).isZero();
        assertThat(created.getEnabled()).isTrue();
        verify(annotationMapper).insert(annotation);
    }

    @Test
    void rejectsMutationWhenAnnotationDoesNotBelongToApplication() {
        AppAnnotationMapper annotationMapper = mock(AppAnnotationMapper.class);
        AppAnnotationService annotationService = new AppAnnotationService(annotationMapper);

        assertThatThrownBy(() -> annotationService.update(
                "app-a", "annotation-from-app-b", new AppAnnotationEntity()))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("annotation-from-app-b");

        verify(annotationMapper, never()).updateById(any(AppAnnotationEntity.class));
    }

    @Test
    void updatesOnlyEditableAnnotationFields() {
        AppAnnotationMapper annotationMapper = mock(AppAnnotationMapper.class);
        AppAnnotationEntity annotation = annotation("annotation-1", "app-1");
        annotation.setTenantId("tenant-1");
        when(annotationMapper.selectOne(any())).thenReturn(annotation);
        AppAnnotationService annotationService = new AppAnnotationService(annotationMapper);
        AppAnnotationEntity updates = annotation("client-id", "other-app");
        updates.setTenantId("other-tenant");
        updates.setQuestion("Improved question");
        updates.setContent("Improved answer");

        AppAnnotationEntity updated = annotationService.update("app-1", "annotation-1", updates);

        assertThat(updated.getId()).isEqualTo("annotation-1");
        assertThat(updated.getAppId()).isEqualTo("app-1");
        assertThat(updated.getTenantId()).isEqualTo("tenant-1");
        assertThat(updated.getQuestion()).isEqualTo("Improved question");
        assertThat(updated.getContent()).isEqualTo("Improved answer");
        verify(annotationMapper).updateById(annotation);
    }

    @Test
    void recordsFirstHitFromAnUninitializedCounter() {
        AppAnnotationMapper annotationMapper = mock(AppAnnotationMapper.class);
        AppAnnotationEntity annotation = annotation("annotation-1", "app-1");
        when(annotationMapper.selectOne(any())).thenReturn(annotation);
        AppAnnotationService annotationService = new AppAnnotationService(annotationMapper);

        annotationService.recordHit("app-1", "annotation-1");

        assertThat(annotation.getHitCount()).isEqualTo(1);
        verify(annotationMapper).updateById(annotation);
    }

    private static AppAnnotationEntity annotation(String annotationId, String appId) {
        AppAnnotationEntity annotation = new AppAnnotationEntity();
        annotation.setId(annotationId);
        annotation.setAppId(appId);
        return annotation;
    }
}
