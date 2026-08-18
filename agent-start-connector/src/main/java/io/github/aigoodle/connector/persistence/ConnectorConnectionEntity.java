package io.github.aigoodle.connector.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.aigoodle.common.persistence.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_connector_connection")
public class ConnectorConnectionEntity extends BaseEntity {
    private String installationId;
    private String name;
    private String encryptedCredentials;
    private String encryptedConfig;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime lastTestedAt;
}
