package io.github.aigoodle.agent.runtime;

import io.github.aigoodle.agent.api.AgentDefinition;
import io.github.aigoodle.agent.api.AgentRequest;
import io.github.aigoodle.agent.api.AgentResponse;
import io.github.aigoodle.common.exception.PlatformException;
import io.github.aigoodle.common.util.JsonUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Lightweight store for tests and applications that deliberately opt out of JDBC. */
public class InMemoryAgentRunStore implements AgentRunStore {
    private final ConcurrentHashMap<String, AgentRunSnapshot> runs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<AgentRunEvent>> eventStreams = new ConcurrentHashMap<>();

    @Override
    public AgentRunSnapshot create(String runId, AgentDefinition definition, AgentRequest request,
                                   String conversationId) {
        LocalDateTime now = LocalDateTime.now();
        AgentRunSnapshot created = new AgentRunSnapshot(runId,
                definition.getTenantId() == null || definition.getTenantId().isBlank()
                        ? "default" : definition.getTenantId(),
                definition.getId(), conversationId, AgentRunStatus.CREATED,
                JsonUtils.toJson(definition), JsonUtils.toJson(request), null, null,
                0, null, null, now, now);
        if (runs.putIfAbsent(runId, created) != null) {
            throw new PlatformException("agent_run_exists", "Agent run already exists: " + runId, null);
        }
        append(created, "RUN_CREATED", created.requestJson());
        return created;
    }

    @Override
    public AgentRunSnapshot transition(String runId, AgentRunStatus target,
                                       AgentResponse response, String error) {
        AgentRunSnapshot updated = runs.compute(runId, (id, current) -> {
            if (current == null) {
                throw new PlatformException("agent_run_not_found", "Agent run not found: " + runId, null);
            }
            if (!current.status().canTransitionTo(target)) {
                throw new PlatformException("invalid_run_transition",
                        "Agent run " + runId + " cannot transition from "
                                + current.status() + " to " + target, null);
            }
            LocalDateTime now = LocalDateTime.now();
            return new AgentRunSnapshot(current.runId(), current.tenantId(), current.agentId(),
                    current.conversationId(), target, current.definitionJson(), current.requestJson(),
                    response == null ? current.responseJson() : JsonUtils.toJson(response),
                    error == null ? current.error() : error, current.version() + 1,
                    target == AgentRunStatus.RUNNING && current.startedAt() == null
                            ? now : current.startedAt(),
                    target.isTerminal() ? now : current.finishedAt(),
                    current.createdAt(), now);
        });
        append(updated, "RUN_" + target.name(),
                response == null ? error : JsonUtils.toJson(response));
        return updated;
    }

    @Override
    public Optional<AgentRunSnapshot> find(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    @Override
    public List<AgentRunEvent> events(String runId, long afterSequence, int limit) {
        int pageSize = Math.max(1, limit);
        synchronized (eventStreams.computeIfAbsent(runId, ignored -> new ArrayList<>())) {
            return eventStreams.get(runId).stream()
                    .filter(event -> event.sequence() > afterSequence)
                    .limit(pageSize)
                    .toList();
        }
    }

    @Override
    public void appendEvent(String runId, String type, String payloadJson) {
        AgentRunSnapshot run = runs.get(runId);
        if (run == null) {
            throw new PlatformException("agent_run_not_found", "Agent run not found: " + runId, null);
        }
        append(run, type, payloadJson);
    }

    private void append(AgentRunSnapshot run, String type, String payload) {
        List<AgentRunEvent> events = eventStreams.computeIfAbsent(run.runId(), ignored -> new ArrayList<>());
        synchronized (events) {
            events.add(new AgentRunEvent(UUID.randomUUID().toString(), run.runId(),
                    events.size() + 1L, type, payload, LocalDateTime.now()));
        }
    }
}
