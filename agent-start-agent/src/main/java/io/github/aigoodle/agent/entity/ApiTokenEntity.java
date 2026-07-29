package io.github.aigoodle.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.aigoodle.common.persistence.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * A per-app API access token. Ported from the legacy {@code api_tokens} table.
 * Consumers hit the public chat/completions endpoints with this token in the
 * {@code Authorization: Bearer <token>} header.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("api_tokens")
public class ApiTokenEntity extends BaseEntity {

    /** FK to {@code apps.id}. */
    private String appId;

    /** {@code app} / {@code dataset} — scope of the token. Defaults to {@code app}. */
    private String type;

    /** Human-readable label the user assigns to the token. */
    private String name;

    /** The token value itself (opaque string, generated on create). */
    private String token;

    /** Optional: when the token was last used (bumped by the runtime — MVP unused). */
    private java.time.LocalDateTime lastUsedAt;
}
