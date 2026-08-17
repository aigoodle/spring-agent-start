package io.github.aigoodle.web.controller;

import io.github.aigoodle.agent.runtime.AgentRunEvent;
import io.github.aigoodle.agent.runtime.AgentRunSnapshot;
import io.github.aigoodle.agent.runtime.AgentRuntime;
import io.github.aigoodle.agent.runtime.AgentResumeCommand;
import io.github.aigoodle.agent.api.AgentResponse;
import io.github.aigoodle.common.exception.PlatformException;
import io.github.aigoodle.web.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only execution inspection API shared by consoles and embedded UI components. */
@RestController
@ConditionalOnBean(AgentRuntime.class)
@RequestMapping("/agent-runs")
public class AgentRunController {
    private static final int DEFAULT_EVENT_LIMIT = 200;

    private final AgentRuntime agentRuntime;

    public AgentRunController(AgentRuntime agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    @GetMapping("/{runId}")
    public ApiResponse<AgentRunSnapshot> get(@PathVariable String runId) {
        return ApiResponse.ok(agentRuntime.findRun(runId).orElseThrow(() ->
                new PlatformException("agent_run_not_found", "Agent run not found: " + runId, null)));
    }

    @GetMapping("/{runId}/events")
    public ApiResponse<List<AgentRunEvent>> events(
            @PathVariable String runId,
            @RequestParam(defaultValue = "0") long afterSequence,
            @RequestParam(defaultValue = "200") int limit) {
        agentRuntime.findRun(runId).orElseThrow(() ->
                new PlatformException("agent_run_not_found", "Agent run not found: " + runId, null));
        return ApiResponse.ok(agentRuntime.runEvents(
                runId, Math.max(0, afterSequence), Math.min(1000, Math.max(1, limit))));
    }

    @PostMapping("/{runId}/resume")
    public ApiResponse<AgentResponse> resume(@PathVariable String runId,
                                             @RequestBody AgentResumeCommand command) {
        return ApiResponse.ok(agentRuntime.resume(runId, command));
    }

    @PostMapping("/{runId}/cancel")
    public ApiResponse<AgentRunSnapshot> cancel(@PathVariable String runId) {
        return ApiResponse.ok(agentRuntime.cancel(runId));
    }
}
