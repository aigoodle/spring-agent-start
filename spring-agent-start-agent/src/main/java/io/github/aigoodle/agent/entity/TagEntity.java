package io.github.aigoodle.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.aigoodle.common.persistence.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * A tenant-scoped organisational tag applied to apps or knowledge bases
 * (Dify parity — the sidebar filter chips). Many-to-many bindings live in
 * {@link TagBindingEntity}.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tags")
public class TagEntity extends BaseEntity {

    /** {@code app} / {@code knowledge}. */
    private String type;

    /** Display name — unique per (tenant, type). */
    private String name;
}
