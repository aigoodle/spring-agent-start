package io.github.aigoodle.workflow.engine;

/** Durable vocabulary for workflow run lifecycle state. */
public enum WorkflowRunStatus {
    RUNNING,
    PAUSING,
    PAUSED,
    CANCELLING,
    CANCELLED,
    TIMED_OUT,
    SUCCEEDED,
    FAILED,
    WAITING
}
