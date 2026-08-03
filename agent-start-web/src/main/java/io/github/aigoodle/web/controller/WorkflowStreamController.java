package io.github.aigoodle.web.controller;

import io.github.aigoodle.web.common.SseEmitterBridge;
import io.github.aigoodle.web.dto.WorkflowRunRequest;
import io.github.aigoodle.workflow.engine.WorkflowRunResult;
import io.github.aigoodle.workflow.service.WorkflowService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;

/**
 * SSE streaming run endpoints for workflows.
 * <p>
 * MVC-only by design: the handler signatures reference {@link SseEmitter}, a
 * spring-webmvc type. The controller is therefore registered only when
 * spring-webmvc is on the classpath; on reactive hosts (e.g.
 * {@code agent-start-server}, which excludes {@code spring-boot-starter-web})
 * component scan skips it, and streaming runs are served by the
 * {@code agent-start-completion} module instead. Keeping these methods out of
 * {@link WorkflowController} matters — WebFlux's handler mapping introspects
 * every registered controller's method signatures at startup and would fail on
 * the missing servlet class.
 */
@RestController
@ConditionalOnClass(SseEmitter.class)
@ConditionalOnBean(WorkflowService.class)
@RequestMapping("${spring-agent.web.base-path:}")
public class WorkflowStreamController {

    private final WorkflowService workflowService;

    public WorkflowStreamController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @PostMapping(value = "/workflows/run-graph/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runGraphStream(@RequestBody WorkflowRunRequest request) {
        return SseEmitterBridge.stream(emitter -> {
            emitter.event("run-start", Map.of(
                    "conversationId", emptyIfNull(request.getConversationId())));
            WorkflowRunResult result = request.getGraph() != null
                    ? workflowService.runGraph(
                            request.getGraph(), inputsOf(request), request.getConversationId(),
                            step -> emitter.event("step", step))
                    : workflowService.run(
                            request.getWorkflowId(), inputsOf(request), request.getConversationId(),
                            step -> emitter.event("step", step));
            emitter.event("result", result);
        });
    }

    @PostMapping(value = "/workflows/{id}/run/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runStream(@PathVariable String id, @RequestBody WorkflowRunRequest request) {
        return SseEmitterBridge.stream(emitter -> {
            emitter.event("run-start", Map.of("workflowId", id));
            WorkflowRunResult result = workflowService.run(
                    id, inputsOf(request), request.getConversationId(),
                    step -> emitter.event("step", step));
            emitter.event("result", result);
        });
    }

    private static Map<String, Object> inputsOf(WorkflowRunRequest request) {
        return request.getInputs() == null ? new HashMap<>() : request.getInputs();
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
