package io.github.aigoodle.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.aigoodle.common.persistence.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_runs")
public class AgentRunEntity extends BaseEntity {
    private String agentId;
    private String conversationId;
    private String status;
    private String definitionJson;
    private String requestJson;
    private String responseJson;
    private String error;
    private Long version;
    private Long eventSequence;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
