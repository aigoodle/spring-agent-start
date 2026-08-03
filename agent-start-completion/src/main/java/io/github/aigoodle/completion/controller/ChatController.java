package io.github.aigoodle.completion.controller;

import io.github.aigoodle.completion.dto.dify.DifyChatMessagesRequest;
import io.github.aigoodle.completion.dto.openai.OpenAIChatRequest;
import io.github.aigoodle.completion.dto.openai.OpenAIChatResponse;
import io.github.aigoodle.completion.service.AppGenerateService;
import io.github.aigoodle.completion.service.ConversationHistoryService;
import io.github.aigoodle.completion.support.AppAccessResolver;
import io.github.aigoodle.completion.support.DifyChatAdapter;
import io.github.aigoodle.web.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/** Reactive OpenAI/Dify chat facade plus console conversation-history endpoints. */
@RestController
@ConditionalOnBean(AppGenerateService.class)
@RequestMapping("${spring-agent.web.base-path:}")
public class ChatController {

    private static final Scheduler BLOCKING_SCHEDULER = Schedulers.fromExecutorService(
            Executors.newVirtualThreadPerTaskExecutor(), "chat-blocking");

    private final AppGenerateService appGenerateService;
    private final ConversationHistoryService conversationHistoryService;
    private final AppAccessResolver appAccessResolver;

    public ChatController(AppGenerateService appGenerateService,
                          AppAccessResolver appAccessResolver,
                          ConversationHistoryService conversationHistoryService) {
        this.appGenerateService = appGenerateService;
        this.conversationHistoryService = conversationHistoryService;
        this.appAccessResolver = appAccessResolver;
    }

    @PostMapping(
            value = "/chat/completions/{appId}",
            consumes = {MediaType.APPLICATION_JSON_VALUE,
                    MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8"},
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public ResponseEntity<?> completions(
            @PathVariable String appId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorizationHeader,
            @RequestHeader(value = "X-Debug-Mode", required = false) String debugHeader,
            @RequestHeader(value = "X-Workflow-Id", required = false) String workflowIdHeader,
            @RequestParam(value = "debug", required = false) String debugParameter,
            @RequestParam(value = "workflowId", required = false) String workflowIdParameter,
            @RequestBody OpenAIChatRequest request) {
        mergeDebugParameters(request, debugHeader, debugParameter,
                workflowIdHeader, workflowIdParameter);
        String resolvedAppId = appAccessResolver.enforcePathApp(
                appId, authorizationHeader, isDebugRun(request));
        if (request.streaming()) {
            Flux<ServerSentEvent<Object>> stream =
                    appGenerateService.generateStream(resolvedAppId, request);
            return eventStream(stream);
        }
        Mono<OpenAIChatResponse> response = Mono.fromCallable(
                        () -> appGenerateService.generateBlocking(resolvedAppId, request))
                .subscribeOn(BLOCKING_SCHEDULER);
        return json(response);
    }

    @PostMapping(
            value = "/chat-messages",
            consumes = {MediaType.APPLICATION_JSON_VALUE,
                    MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8"},
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public ResponseEntity<?> chatMessages(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorizationHeader,
            @RequestHeader(value = "X-App-Id", required = false) String appIdHeader,
            @RequestHeader(value = "X-Debug-Mode", required = false) String debugHeader,
            @RequestHeader(value = "X-Workflow-Id", required = false) String workflowIdHeader,
            @RequestParam(value = "appId", required = false) String appIdParameter,
            @RequestParam(value = "debug", required = false) String debugParameter,
            @RequestParam(value = "workflowId", required = false) String workflowIdParameter,
            @RequestBody DifyChatMessagesRequest request) {
        String appId = appAccessResolver.resolveDifyApp(
                appIdParameter, appIdHeader, authorizationHeader, request);
        OpenAIChatRequest internalRequest = DifyChatAdapter.toInternalRequest(request);
        mergeDebugParameters(internalRequest,
                AppAccessResolver.firstNonBlank(debugHeader,
                        request.getDebug() == null ? null : request.getDebug().toString()),
                debugParameter,
                AppAccessResolver.firstNonBlank(workflowIdHeader, request.getWorkflowId()),
                workflowIdParameter);

        if (request.streaming()) {
            Flux<ServerSentEvent<Object>> stream =
                    appGenerateService.generateDifyStream(appId, internalRequest);
            return eventStream(stream);
        }
        Mono<Map<String, Object>> response = Mono.fromCallable(() -> {
                    OpenAIChatResponse generated =
                            appGenerateService.generateBlocking(appId, internalRequest);
                    return DifyChatAdapter.toBlockingResponse(
                            generated, internalRequest.getConversationId());
                })
                .subscribeOn(BLOCKING_SCHEDULER);
        return json(response);
    }

    @PostMapping(
            value = "/chat/conversations/{appId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<Map<String, Object>>> listConversations(
            @PathVariable String appId,
            @RequestBody(required = false) HistoryQuery query) {
        int limit = resolveLimit(query, 100, 500);
        return ApiResponse.ok(conversationHistoryService.conversations(appId, limit));
    }

    @PostMapping(
            value = "/chat/conversations/{appId}/{conversationId}/messages",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<Map<String, Object>>> conversationMessages(
            @PathVariable String appId,
            @PathVariable String conversationId,
            @RequestBody(required = false) HistoryQuery query) {
        int limit = resolveLimit(query, 500, 500);
        return ApiResponse.ok(conversationHistoryService.messages(
                appId, conversationId, limit));
    }

    private static ResponseEntity<?> eventStream(Flux<ServerSentEvent<Object>> stream) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(stream);
    }

    private static ResponseEntity<?> json(Object body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static boolean isDebugRun(OpenAIChatRequest request) {
        return request != null && Boolean.TRUE.equals(request.getDebug());
    }

    private static void mergeDebugParameters(OpenAIChatRequest request,
                                             String debugHeader,
                                             String debugParameter,
                                             String workflowIdHeader,
                                             String workflowIdParameter) {
        if (request == null) {
            return;
        }
        if (request.getDebug() == null) {
            Boolean debug = parseBooleanFlag(
                    AppAccessResolver.firstNonBlank(debugHeader, debugParameter));
            if (debug != null) {
                request.setDebug(debug);
            }
        }
        if (request.getWorkflowId() == null || request.getWorkflowId().isBlank()) {
            String workflowId = AppAccessResolver.firstNonBlank(
                    workflowIdHeader, workflowIdParameter);
            if (workflowId != null) {
                request.setWorkflowId(workflowId);
            }
        }
    }

    private static Boolean parseBooleanFlag(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on", "enable", "enabled" -> Boolean.TRUE;
            case "false", "0", "no", "off", "disable", "disabled" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static int resolveLimit(HistoryQuery query, int defaultValue, int maximum) {
        if (query == null || query.limit == null || query.limit <= 0) {
            return defaultValue;
        }
        return Math.min(maximum, query.limit);
    }

    public static class HistoryQuery {

        public Integer limit;

        public Integer getLimit() {
            return limit;
        }

        public void setLimit(Integer limit) {
            this.limit = limit;
        }
    }
}
