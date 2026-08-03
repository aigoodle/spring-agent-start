package io.github.aigoodle.memory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.aigoodle.common.persistence.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_memories")
public class MemoryEntity extends BaseEntity {
    private String ownerId;
    private String conversationId;
    private String tier;
    private String role;
    private String content;
    private Double importance;
    private LocalDateTime expiresAt;
    private Integer accessCount;
}
