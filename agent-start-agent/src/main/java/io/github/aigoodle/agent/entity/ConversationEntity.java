package io.github.aigoodle.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.aigoodle.common.persistence.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * A chat session grouping many {@link AgentMessageEntity} rows under one app.
 * Ported from the legacy {@code conversations} table (Dify parity). The
 * {@code messages} table already carries {@code conversation_id} — this row
 * gives the frontend a place to attach names, pin flags, summaries and audit
 * timestamps without walking the whole message history.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("conversations")
public class ConversationEntity extends BaseEntity {

    /** FK to {@code apps.id} — the conversation belongs to a single app. */
    private String appId;

    /** Human-readable title (auto-generated from first user message, editable). */
    private String name;

    /** Auto-generated one-line summary for the sidebar. */
    private String summary;

    /** Introduction / greeting captured at start (usually the app's opening statement). */
    private String introduction;

    /** {@code web} / {@code api} / {@code debugger} — where this conversation came from. */
    private String fromSource;

    /** End-user id when the conversation was started from a public site / api. */
    private String fromEndUserId;

    /** Backend account id when the conversation was started from the console. */
    private String fromAccountId;

    /** {@code normal} / {@code archived}. */
    private String status;

    /** Pinned to the top of the sidebar. */
    private Boolean pinned;
}
