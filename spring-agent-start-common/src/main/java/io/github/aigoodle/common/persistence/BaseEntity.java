package io.github.aigoodle.common.persistence;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Common audit columns shared by every persisted entity across the modules.
 * Uses an assigned String (UUID) id so the modules stay database-portable.
 */
@Data
public abstract class BaseEntity implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** Optional multi-tenant discriminator; {@code "default"} when tenancy is unused. */
    @TableField("tenant_id")
    private String tenantId;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
