package io.github.aigoodle.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.aigoodle.common.persistence.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * A user-annotated question/answer pair for an app (Dify parity — the "标注回复"
 * feature). When a chat query is close enough to {@link #question}, the app
 * returns {@link #content} verbatim instead of calling the LLM.
 * <p>
 * MVP scope: the row here holds the raw pair + a hit counter. Threshold /
 * retrieval-method settings live per-app on the app row itself (or a follow-up
 * settings table); this MVP treats "matching" as an exact / normalised-string
 * lookup, and future work will wire an embedding recall.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("app_annotations")
public class AppAnnotationEntity extends BaseEntity {

    /** FK to {@code apps.id} — the annotation belongs to a single app. */
    private String appId;

    /** The user-facing prompt this annotation overrides. */
    private String question;

    /** The canned answer returned when the question is hit. */
    private String content;

    /** How many times this annotation has been served. Defaults to 0. */
    private Integer hitCount;

    /**
     * Draft vs live gate: {@code false} keeps the pair authored but never
     * served. Defaults to {@code true}.
     */
    private Boolean enabled;
}
