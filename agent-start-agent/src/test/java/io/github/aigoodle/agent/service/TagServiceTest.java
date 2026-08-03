package io.github.aigoodle.agent.service;

import io.github.aigoodle.agent.entity.TagBindingEntity;
import io.github.aigoodle.agent.entity.TagEntity;
import io.github.aigoodle.agent.mapper.TagBindingMapper;
import io.github.aigoodle.agent.mapper.TagMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TagServiceTest {

    @Test
    void createsTagWithNormalizedDefaults() {
        TagMapper tagMapper = mock(TagMapper.class);
        TagService tagService = new TagService(tagMapper, mock(TagBindingMapper.class));
        TagEntity tag = new TagEntity();
        tag.setTenantId(" ");
        tag.setType("");

        TagEntity createdTag = tagService.create(tag);

        assertThat(createdTag).isSameAs(tag);
        assertThat(createdTag.getTenantId()).isEqualTo("default");
        assertThat(createdTag.getType()).isEqualTo("app");
        verify(tagMapper).insert(tag);
    }

    @Test
    void newBindingInheritsTenantAndResolvedTargetType() {
        TagMapper tagMapper = mock(TagMapper.class);
        TagBindingMapper bindingMapper = mock(TagBindingMapper.class);
        TagEntity tag = tag("tag-1", "tenant-1");
        when(tagMapper.selectById(tag.getId())).thenReturn(tag);
        TagService tagService = new TagService(tagMapper, bindingMapper);

        tagService.bind(tag.getId(), "target-1", " ");

        ArgumentCaptor<TagBindingEntity> insertedBinding =
                ArgumentCaptor.forClass(TagBindingEntity.class);
        verify(bindingMapper).insert(insertedBinding.capture());
        assertThat(insertedBinding.getValue().getTenantId()).isEqualTo("tenant-1");
        assertThat(insertedBinding.getValue().getTargetType()).isEqualTo("app");
    }

    @Test
    void existingTypedBindingIsNotInsertedAgain() {
        TagMapper tagMapper = mock(TagMapper.class);
        TagBindingMapper bindingMapper = mock(TagBindingMapper.class);
        TagEntity tag = tag("tag-1", "tenant-1");
        when(tagMapper.selectById(tag.getId())).thenReturn(tag);
        when(bindingMapper.selectOne(any())).thenReturn(new TagBindingEntity());
        TagService tagService = new TagService(tagMapper, bindingMapper);

        tagService.bind(tag.getId(), "target-1", "knowledge");

        verify(bindingMapper, never()).insert(any(TagBindingEntity.class));
    }

    @Test
    void deletesBindingsBeforeTheirTag() {
        TagMapper tagMapper = mock(TagMapper.class);
        TagBindingMapper bindingMapper = mock(TagBindingMapper.class);
        TagService tagService = new TagService(tagMapper, bindingMapper);

        tagService.delete("tag-1");

        InOrder deletionOrder = inOrder(bindingMapper, tagMapper);
        deletionOrder.verify(bindingMapper).delete(any());
        deletionOrder.verify(tagMapper).deleteById("tag-1");
    }

    private static TagEntity tag(String tagId, String tenantId) {
        TagEntity tag = new TagEntity();
        tag.setId(tagId);
        tag.setTenantId(tenantId);
        return tag;
    }
}
