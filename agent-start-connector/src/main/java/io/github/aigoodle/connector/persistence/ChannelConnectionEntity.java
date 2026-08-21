package io.github.aigoodle.connector.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.aigoodle.common.persistence.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_channel_connection")
public class ChannelConnectionEntity extends BaseEntity {
    private String ownerType;
    private String ownerId;
    private String provider;
    private String channelId;
    private String name;
    private String encryptedCredentials;
    private String encryptedConfig;
    private String desiredStatus;
    private String runtimeStatus;
    private String runtimeAccountId;
    private String runtimeNodeId;
    private String agentId;
    private String agentVersionId;
    private String runtimeMetadataJson;
    private String lastError;
    private LocalDateTime lastTestedAt;
    private Long configVersion;
    private Integer reconcileAttempts;
    private LocalDateTime nextReconcileAt;
    private LocalDateTime reconcileLeaseUntil;
    private String reconcileLeaseOwner;
}
