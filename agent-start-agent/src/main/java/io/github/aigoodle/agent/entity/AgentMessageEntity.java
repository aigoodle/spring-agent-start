package io.github.aigoodle.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.aigoodle.common.persistence.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * A persisted conversation message backing {@code JdbcAgentMemory}.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("messages")
public class AgentMessageEntity extends BaseEntity {

    private String conversationId;

    /** Optional owner of the conversation — {@code null} for ad-hoc definition runs. */
    private String agentId;

    private String role;

    private String content;

    /** Monotonic sequence within a conversation for stable ordering. */
    private Long seq;
}
