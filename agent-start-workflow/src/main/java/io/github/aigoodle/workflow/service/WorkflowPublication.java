package io.github.aigoodle.workflow.service;

/** Human-readable metadata attached to one immutable published workflow snapshot. */
public record WorkflowPublication(String versionName, String comment) {
}
