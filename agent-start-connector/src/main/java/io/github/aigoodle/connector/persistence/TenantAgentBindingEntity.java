package io.github.aigoodle.connector.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.aigoodle.common.persistence.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_tenant_agent_binding")
public class TenantAgentBindingEntity extends BaseEntity {
    private String defaultAgentId;
    private String defaultAgentVersionId;
    private String fallbackAgentId;
    private String fallbackAgentVersionId;
    private Long routingPolicyVersion;
    private Boolean enabled;
}
