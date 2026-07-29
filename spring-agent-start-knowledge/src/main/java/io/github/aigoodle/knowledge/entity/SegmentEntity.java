package io.github.aigoodle.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.aigoodle.common.persistence.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * A single chunk: the unit that gets embedded, stored in the vector store and
 * returned by retrieval. Keyword tokens and metadata are persisted so keyword
 * retrieval and metadata filtering work without an external search engine.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("document_segments")
public class SegmentEntity extends BaseEntity {

    private String datasetId;

    private String documentId;

    /** Ordinal position of this chunk within its document. */
    private Integer position;

    private String content;

    private Integer tokenCount;

    /** Space-joined keyword tokens used by the keyword retriever. */
    private String keywords;

    /** JSON metadata (heading path, page, source name, custom fields). */
    private String metadataJson;

    /** Parent segment id for PARENT_CHILD chunking; null otherwise. */
    private String parentId;

    /** Id used as the vector store document id, so we can delete/update precisely. */
    private String vectorId;

    private Boolean enabled;

    /** Content hash for dedup / change detection. */
    private String hash;
}
