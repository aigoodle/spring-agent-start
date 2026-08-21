package io.github.aigoodle.agent.runtime;

import io.github.aigoodle.agent.api.AgentDefinition;
import io.github.aigoodle.agent.api.AgentRequest;
import io.github.aigoodle.agent.api.AgentStep;

import java.util.function.Consumer;

/** Immutable invocation envelope shared by native and optional Agent runtimes. */
public record AgentRuntimeInvocation(
        String runtimeType,
        AgentDefinition definition,
        AgentRequest request,
        Consumer<AgentStep> stepListener,
        Consumer<String> tokenListener) {
}
