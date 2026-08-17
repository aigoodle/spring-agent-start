package io.github.aigoodle.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.aigoodle.common.persistence.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("goodle_run_events")
public class AgentRunEventEntity extends BaseEntity {
    private String runId;
    private Long sequenceNo;
    private String eventType;
    private String payloadJson;
}
