package io.github.aigoodle.web.controller;

import io.github.aigoodle.agent.runtime.AgentRuntimeRegistry;
import io.github.aigoodle.web.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only capability discovery for embedded Agent editors. */
@RestController
@ConditionalOnBean(AgentRuntimeRegistry.class)
@RequestMapping("/agent-runtimes")
public class AgentRuntimeController {
    public record View(String type, boolean nativeRuntime) {}
    private final AgentRuntimeRegistry runtimes;

    public AgentRuntimeController(AgentRuntimeRegistry runtimes) { this.runtimes = runtimes; }

    @GetMapping
    public ApiResponse<List<View>> list() {
        return ApiResponse.ok(runtimes.runtimeTypes().stream()
                .map(type -> new View(type, AgentRuntimeRegistry.NATIVE.equals(type))).toList());
    }
}
