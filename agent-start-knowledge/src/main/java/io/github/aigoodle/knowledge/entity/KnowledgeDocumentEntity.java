package io.github.aigoodle.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import io.github.aigoodle.common.persistence.BaseEntity;
import io.github.aigoodle.knowledge.enums.DocumentStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * One source document within a dataset (an uploaded file, a piece of text or a URL).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("documents")
public class KnowledgeDocumentEntity extends BaseEntity {

    private String datasetId;

    private String name;

    /** e.g. {@code text}, {@code file}, {@code url}. */
    private String sourceType;

    private DocumentStatus status;

    private String errorMessage;

    private Integer wordCount;

    private Integer segmentCount;

    private String parserName;
    private String mediaType;
    private Integer pageCount;
    private Integer blockCount;
    private String parseWarningsJson;
    @TableField(select = false)
    private String parsedDocumentJson;

    @TableField(select = false)
    private String sourceDataBase64;
    private Long fileSize;
    private String sourceChecksum;

    private Boolean enabled;
}
