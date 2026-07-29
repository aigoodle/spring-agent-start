package io.github.aigoodle.completion.controller;

import io.github.aigoodle.agent.api.AgentMessage;
import io.github.aigoodle.agent.entity.ApiTokenEntity;
import io.github.aigoodle.agent.entity.ConversationEntity;
import io.github.aigoodle.agent.memory.AgentMemory;
import io.github.aigoodle.agent.service.ApiTokenService;
import io.github.aigoodle.agent.service.ConversationService;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.completion.dto.dify.DifyChatMessagesRequest;
import io.github.aigoodle.completion.dto.openai.OpenAIChatRequest;
import io.github.aigoodle.completion.dto.openai.OpenAIChatResponse;
import io.github.aigoodle.completion.dto.openai.OpenAIMessage;
import io.github.aigoodle.completion.service.AppGenerateService;
import io.github.aigoodle.web.common.ApiResponse;
import org.springframework.beans.factory.ObjectProvider;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * OpenAI-compatible chat surface for a hosted app, plus the small set of
 * console-side history endpoints that show up in the app-design drawer's
 * 日志与标注 tab. Split out of spring-agent-start-web so the streaming stack can
 * stay on Netty/WebFlux while the rest of the REST surface runs on MVC.
 */
@RestController
@ConditionalOnBean(AppGenerateService.class)
@RequestMapping("${spring-agent.web.base-path:}")
public class ChatController {

    private static final Scheduler BLOCKING_SCHEDULER = Schedulers.fromExecutorService(
            Executors.newVirtualThreadPerTaskExecutor(), "chat-blocking");

    private final AppGenerateService appGenerateService;
    private final ObjectProvider<ConversationService> conversationServiceProvider;
    private final ObjectProvider<AgentMemory> agentMemoryProvider;
    private final ObjectProvider<ApiTokenService> apiTokenServiceProvider;

    public ChatController(AppGenerateService appGenerateService,
                          ObjectProvider<ConversationService> conversationServiceProvider,
                          ObjectProvider<AgentMemory> agentMemoryProvider,
                          ObjectProvider<ApiTokenService> apiTokenServiceProvider) {
        this.appGenerateService = appGenerateService;
        this.conversationServiceProvider = conversationServiceProvider;
        this.agentMemoryProvider = agentMemoryProvider;
        this.apiTokenServiceProvider = apiTokenServiceProvider;
    }

    @PostMapping(
            value = "/chat/completions/{appId}",
            consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8"},
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public ResponseEntity<?> completions(@PathVariable String appId,
                                         @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                         @RequestHeader(value = "X-Debug-Mode", required = false) String debugHeader,
                                         @RequestHeader(value = "X-Workflow-Id", required = false) String workflowIdHeader,
                                         @RequestParam(value = "debug", required = false) String debugParam,
                                         @RequestParam(value = "workflowId", required = false) String workflowIdParam,
                                         @RequestBody OpenAIChatRequest request) {
        mergeDebugParams(request, debugHeader, debugParam, workflowIdHeader, workflowIdParam);
        final String resolvedAppId = enforceApiKey(appId, auth, isDebugRun(request));
        if (request.streaming()) {
            Flux<ServerSentEvent<Object>> stream = appGenerateService.generateStream(resolvedAppId, request);
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(stream);
        }
        Mono<OpenAIChatResponse> body = Mono
                .fromCallable(() -> appGenerateService.generateBlocking(resolvedAppId, request))
                .subscribeOn(BLOCKING_SCHEDULER);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @PostMapping(
            value = "/chat-messages",
            consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8"},
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public ResponseEntity<?> chatMessages(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = "X-App-Id", required = false) String appIdHeader,
            @RequestHeader(value = "X-Debug-Mode", required = false) String debugHeader,
            @RequestHeader(value = "X-Workflow-Id", required = false) String workflowIdHeader,
            @RequestParam(value = "appId", required = false) String appIdParam,
            @RequestParam(value = "debug", required = false) String debugParam,
            @RequestParam(value = "workflowId", required = false) String workflowIdParam,
            @RequestBody DifyChatMessagesRequest body) {
        String appId = resolveAppIdWithApiKey(appIdParam, appIdHeader, auth, body);
        OpenAIChatRequest internal = toInternalRequest(body);
        mergeDebugParams(internal,
                firstNonBlank(debugHeader, body.getDebug() == null ? null : body.getDebug().toString()),
                debugParam,
                firstNonBlank(workflowIdHeader, body.getWorkflowId()),
                workflowIdParam);
        if (body.streaming()) {
            Flux<ServerSentEvent<Object>> stream = appGenerateService.generateDifyStream(appId, internal);
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(stream);
        }
        Mono<Map<String, Object>> mono = Mono
                .fromCallable(() -> {
                    OpenAIChatResponse resp = appGenerateService.generateBlocking(appId, internal);
                    return toDifyBlockingBody(resp, internal.getConversationId());
                })
                .subscribeOn(BLOCKING_SCHEDULER);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mono);
    }

    private String resolveAppIdWithApiKey(String queryParam, String headerParam,
                                          String authHeader, DifyChatMessagesRequest body) {
        String bearer = extractBearerToken(authHeader);
        String fromApiKey = resolveByApiKey(bearer);
        if (fromApiKey != null) return fromApiKey;
        return resolveAppId(queryParam, headerParam, authHeader, body);
    }

    private String enforceApiKey(String pathAppId, String authHeader, boolean debugRun) {
        if (debugRun) return pathAppId;
        ApiTokenService svc = apiTokenServiceProvider.getIfAvailable();
        if (svc == null) return pathAppId;
        String bearer = extractBearerToken(authHeader);
        if (bearer == null) return pathAppId;
        ApiTokenEntity token = svc.findByToken(bearer);
        if (token == null) return pathAppId;
        if (pathAppId != null && !pathAppId.isBlank() && !pathAppId.equals(token.getAppId())) {
            throw new AgentException("api_key_app_mismatch",
                    "该 API Key 不属于目标应用 " + pathAppId, null);
        }
        svc.touchLastUsed(token.getId());
        return token.getAppId();
    }

    private String resolveByApiKey(String bearer) {
        if (bearer == null) return null;
        ApiTokenService svc = apiTokenServiceProvider.getIfAvailable();
        if (svc == null) return null;
        ApiTokenEntity token = svc.findByToken(bearer);
        if (token == null) return null;
        svc.touchLastUsed(token.getId());
        return token.getAppId();
    }

    private static boolean isDebugRun(OpenAIChatRequest request) {
        return request != null && Boolean.TRUE.equals(request.getDebug());
    }

    private static String resolveAppId(String queryParam, String headerParam,
                                       String authHeader, DifyChatMessagesRequest body) {
        String fromQuery = trimToNull(queryParam);
        if (fromQuery != null) return fromQuery;
        String fromHeader = trimToNull(headerParam);
        if (fromHeader != null) return fromHeader;
        String fromAuth = extractBearerToken(authHeader);
        if (fromAuth != null) return fromAuth;
        if (body != null) {
            String fromBody = trimToNull(body.getAppId());
            if (fromBody != null) return fromBody;
            if (body.getInputs() != null) {
                Object raw = body.getInputs().get("app_id");
                if (raw == null) raw = body.getInputs().get("appId");
                String fromInputs = raw == null ? null : trimToNull(raw.toString());
                if (fromInputs != null) return fromInputs;
            }
        }
        throw new AgentException("missing_app_id",
                "无法识别目标应用：请通过 ?appId= / X-App-Id / Authorization: Bearer / body.app_id / inputs.app_id 之一提供",
                null);
    }

    private static void mergeDebugParams(OpenAIChatRequest request,
                                         String debugHeader, String debugQuery,
                                         String workflowIdHeader, String workflowIdQuery) {
        if (request == null) return;
        if (request.getDebug() == null) {
            Boolean parsed = parseBooleanFlag(firstNonBlank(debugHeader, debugQuery));
            if (parsed != null) request.setDebug(parsed);
        }
        if (request.getWorkflowId() == null || request.getWorkflowId().isBlank()) {
            String wf = firstNonBlank(workflowIdHeader, workflowIdQuery);
            if (wf != null) request.setWorkflowId(wf);
        }
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    private static Boolean parseBooleanFlag(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toLowerCase();
        if (v.isEmpty()) return null;
        return switch (v) {
            case "true", "1", "yes", "on", "enable", "enabled" -> Boolean.TRUE;
            case "false", "0", "no", "off", "disable", "disabled" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static String extractBearerToken(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) return null;
        String trimmed = authHeader.trim();
        String token;
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            token = trimmed.substring("Bearer ".length()).trim();
        } else {
            token = trimmed;
        }
        return token.isEmpty() ? null : token;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static OpenAIChatRequest toInternalRequest(DifyChatMessagesRequest src) {
        OpenAIChatRequest r = new OpenAIChatRequest();
        r.setStream(Boolean.TRUE);
        r.setConversationId(src.getConversationId());
        Map<String, Object> inputs = src.getInputs();
        r.setData(inputs == null ? new HashMap<>() : new HashMap<>(inputs));
        if (src.getUser() != null && !src.getUser().isBlank()) {
            r.getData().putIfAbsent("__dify_user", src.getUser());
        }
        List<OpenAIMessage> messages = new ArrayList<>();
        String query = src.getQuery() == null ? "" : src.getQuery();
        messages.add(OpenAIMessage.user(query));
        r.setMessages(messages);
        r.setInvokeFrom("dify-chat-messages");
        return r;
    }

    private static Map<String, Object> toDifyBlockingBody(OpenAIChatResponse resp, String conversationId) {
        String answer = "";
        if (resp.getChoices() != null && !resp.getChoices().isEmpty()) {
            var msg = resp.getChoices().get(0).getMessage();
            if (msg != null && msg.getContent() != null) {
                answer = msg.getContent();
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", "message");
        body.put("id", resp.getId());
        body.put("message_id", resp.getId());
        body.put("conversation_id", conversationId);
        body.put("mode", "chat");
        body.put("answer", answer);
        body.put("created_at", resp.getCreated());
        return body;
    }

    @PostMapping(
            value = "/chat/conversations/{appId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<Map<String, Object>>> listConversations(@PathVariable String appId,
                                                                     @RequestBody(required = false) HistoryQuery body) {
        ConversationService svc = conversationServiceProvider.getIfAvailable();
        if (svc == null) {
            return ApiResponse.ok(List.of());
        }
        int cap = resolveLimit(body, 100, 500);
        AgentMemory memory = agentMemoryProvider.getIfAvailable();
        List<ConversationEntity> rows = svc.listByApp(appId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConversationEntity row : rows) {
            if (out.size() >= cap) {
                break;
            }
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("conversationId", row.getId());
            view.put("name", row.getName());
            view.put("userId", pickUserId(row));
            view.put("firstMessage", firstMessageFor(row, memory));
            view.put("updatedAt", formatDateTime(row.getUpdatedAt(), row.getCreatedAt()));
            view.put("pinned", Boolean.TRUE.equals(row.getPinned()));
            out.add(view);
        }
        return ApiResponse.ok(out);
    }

    @PostMapping(
            value = "/chat/conversations/{appId}/{conversationId}/messages",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<Map<String, Object>>> conversationMessages(@PathVariable String appId,
                                                                        @PathVariable String conversationId,
                                                                        @RequestBody(required = false) HistoryQuery body) {
        AgentMemory memory = agentMemoryProvider.getIfAvailable();
        if (memory == null) {
            return ApiResponse.ok(List.of());
        }
        int cap = resolveLimit(body, 500, 500);
        List<AgentMessage> messages = memory.load(conversationId, cap);
        List<Map<String, Object>> out = new ArrayList<>(messages.size());
        for (AgentMessage m : messages) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("role", m.role() == null ? null : m.role().name());
            row.put("content", m.content());
            out.add(row);
        }
        return ApiResponse.ok(out);
    }

    private static int resolveLimit(HistoryQuery body, int defaultValue, int max) {
        if (body == null || body.limit == null) {
            return defaultValue;
        }
        int v = body.limit;
        if (v <= 0) return defaultValue;
        return Math.min(max, v);
    }

    private static String firstMessageFor(ConversationEntity row, AgentMemory memory) {
        if (row.getSummary() != null && !row.getSummary().isBlank()) {
            return row.getSummary();
        }
        if (memory == null) {
            return row.getName();
        }
        try {
            List<AgentMessage> messages = memory.load(row.getId(), 20);
            for (AgentMessage m : messages) {
                if (m.role() == AgentMessage.Role.USER) {
                    return m.content();
                }
            }
        } catch (Exception ignore) {
        }
        return row.getName();
    }

    private static String formatDateTime(LocalDateTime updated, LocalDateTime created) {
        LocalDateTime pick = updated != null ? updated : created;
        return pick == null ? null : pick.toString();
    }

    private static String pickUserId(ConversationEntity row) {
        String endUser = trimToNull(row.getFromEndUserId());
        if (endUser != null) return endUser;
        return trimToNull(row.getFromAccountId());
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
