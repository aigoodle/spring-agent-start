package io.github.aigoodle.workflow.service;

import io.github.aigoodle.workflow.engine.WorkflowRunResult;

public record WorkflowSignalResult(boolean accepted, boolean duplicate, WorkflowRunResult runResult) {}
