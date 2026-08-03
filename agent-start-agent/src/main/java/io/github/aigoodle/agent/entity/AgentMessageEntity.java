package io.github.aigoodle.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.aigoodle.common.persistence.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** A persisted conversation message used by JDBC-backed agent memory. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("messages")
public class AgentMessageEntity extends BaseEntity {

    private String conversationId;

    /** Owning agent ID, or {@code null} for an ad-hoc definition run. */
    private String agentId;

    private String role;

    private String content;

    /** Monotonic sequence number used for stable conversation ordering. */
    private Long seq;
}
