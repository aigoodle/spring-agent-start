package io.github.aigoodle.knowledge.async;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Message payload published to the ingest queue. Deliberately minimal — every
 * async worker just needs the document's primary key, then it re-hydrates the
 * dataset context + raw text from the DB (via {@link DocumentIngestQueueEntity}).
 * Keeping the message small means huge documents don't bloat the queue.
 *
 * <p>Retry counter is threaded through so consumers can implement backoff or
 * dead-letter after N retries without the DB touching the message body.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentIngestionTask implements Serializable {

    /** FK to {@code documents.id}. */
    private String documentId;

    /**
     * How many times this task has been redelivered. Populated by
     * {@code DocumentIngestionQueue} on republish. Zero on first delivery.
     */
    private int retryCount;
}
