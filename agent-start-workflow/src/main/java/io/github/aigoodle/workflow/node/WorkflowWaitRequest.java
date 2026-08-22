package io.github.aigoodle.workflow.node;

import io.github.aigoodle.workflow.graph.NodeType;

import java.time.Instant;
import java.util.Map;

/** Durable suspension requested by a non-blocking long-running node. */
public record WorkflowWaitRequest(NodeType type, String correlationKey,
                                  Map<String, Object> inputSchema, Instant expiresAt,
                                  Instant wakeAt, String resumeToken) {}
