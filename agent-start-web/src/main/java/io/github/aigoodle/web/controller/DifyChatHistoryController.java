package io.github.aigoodle.web.controller;

import io.github.aigoodle.agent.entity.ConversationEntity;
import io.github.aigoodle.agent.service.ConversationService;
import io.github.aigoodle.web.dto.dify.DifyConversationNameRequest;
import io.github.aigoodle.web.dto.dify.DifyConversationVO;
import io.github.aigoodle.web.dto.dify.DifyMessageVO;
import io.github.aigoodle.web.dto.dify.DifyPage;
import io.github.aigoodle.web.support.DifyAppIdResolver;
import io.github.aigoodle.web.support.DifyHistoryViewMapper;
import io.github.aigoodle.web.support.DifyMessageHistory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Dify-compatible conversation and message-history endpoints. */
@RestController
@ConditionalOnBean(ConversationService.class)
@RequestMapping("${spring-agent.web.base-path:}")
public class DifyChatHistoryController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final ConversationService conversationService;
    private final DifyMessageHistory messageHistory;
    private final DifyHistoryViewMapper viewMapper;

    public DifyChatHistoryController(ConversationService conversationService,
                                     DifyMessageHistory messageHistory,
                                     DifyHistoryViewMapper viewMapper) {
        this.conversationService = conversationService;
        this.messageHistory = messageHistory;
        this.viewMapper = viewMapper;
    }

    @GetMapping("/conversations")
    public DifyPage<DifyConversationVO> listConversations(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = "X-App-Id", required = false) String headerAppId,
            @RequestParam(value = "appId", required = false) String queryAppId,
            @RequestParam(value = "user", required = false) String user,
            @RequestParam(value = "last_id", required = false) String lastConversationId,
            @RequestParam(value = "limit", required = false) Integer requestedPageSize,
            @RequestParam(value = "sort_by", required = false) String sortBy) {
        String appId = DifyAppIdResolver.resolve(queryAppId, headerAppId, authorizationHeader);
        int pageSize = pageSize(requestedPageSize);
        List<ConversationEntity> conversations = conversationService.listByApp(appId);
        conversations.sort(conversationComparator(sortBy));

        int pageStart = indexAfter(conversations, lastConversationId);
        int pageEnd = Math.min(conversations.size(), pageStart + pageSize);
        List<DifyConversationVO> page = conversations.subList(pageStart, pageEnd).stream()
                .map(viewMapper::toConversation)
                .toList();
        return new DifyPage<>(pageSize, pageEnd < conversations.size(), page);
    }

    @DeleteMapping("/conversations/{conversationId}")
    public Map<String, Object> deleteConversation(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = "X-App-Id", required = false) String headerAppId,
            @RequestParam(value = "appId", required = false) String queryAppId,
            @PathVariable String conversationId,
            @RequestBody(required = false) Map<String, Object> body) {
        DifyAppIdResolver.resolve(queryAppId, headerAppId, authorizationHeader);
        conversationService.delete(conversationId);
        return Map.of("result", "success");
    }

    @PostMapping("/conversations/{conversationId}/name")
    public DifyConversationVO renameConversation(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = "X-App-Id", required = false) String headerAppId,
            @RequestParam(value = "appId", required = false) String queryAppId,
            @PathVariable String conversationId,
            @RequestBody(required = false) DifyConversationNameRequest request) {
        DifyAppIdResolver.resolve(queryAppId, headerAppId, authorizationHeader);
        String requestedName = request == null ? null : request.requestedName();
        if (requestedName == null && request != null && request.requestsAutomaticName()) {
            requestedName = messageHistory.suggestTitle(conversationId);
        }
        ConversationEntity conversation = requestedName == null
                ? conversationService.require(conversationId)
                : conversationService.rename(conversationId, requestedName);
        return viewMapper.toConversation(conversation);
    }

    @GetMapping("/messages")
    public DifyPage<DifyMessageVO> listMessages(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = "X-App-Id", required = false) String headerAppId,
            @RequestParam(value = "appId", required = false) String queryAppId,
            @RequestParam(value = "user", required = false) String user,
            @RequestParam("conversation_id") String conversationId,
            @RequestParam(value = "first_id", required = false) String firstMessageId,
            @RequestParam(value = "limit", required = false) Integer requestedPageSize) {
        DifyAppIdResolver.resolve(queryAppId, headerAppId, authorizationHeader);
        int pageSize = pageSize(requestedPageSize);
        List<DifyMessageVO> messages = viewMapper.toMessages(
                conversationId, messageHistory.findAll(conversationId));
        messages.sort(Comparator.comparingLong(
                message -> message.getCreatedAt() == null ? 0L : message.getCreatedAt()));
        messages = messagesBefore(messages, firstMessageId);

        boolean hasMore = messages.size() > pageSize;
        int pageStart = hasMore ? messages.size() - pageSize : 0;
        return new DifyPage<>(pageSize, hasMore, new ArrayList<>(messages.subList(pageStart, messages.size())));
    }

    private static int pageSize(Integer requestedPageSize) {
        if (requestedPageSize == null || requestedPageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(MAX_PAGE_SIZE, requestedPageSize);
    }

    private static int indexAfter(List<ConversationEntity> conversations, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return 0;
        }
        for (int index = 0; index < conversations.size(); index++) {
            if (conversationId.equals(conversations.get(index).getId())) {
                return index + 1;
            }
        }
        return 0;
    }

    private static List<DifyMessageVO> messagesBefore(List<DifyMessageVO> messages, String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return messages;
        }
        for (int index = 0; index < messages.size(); index++) {
            if (messageId.equals(messages.get(index).getId())) {
                return new ArrayList<>(messages.subList(0, index));
            }
        }
        return messages;
    }

    private static Comparator<ConversationEntity> conversationComparator(String sortBy) {
        String ordering = sortBy == null ? "-updated_at" : sortBy.trim().toLowerCase();
        return switch (ordering) {
            case "created_at" -> comparing(ConversationEntity::getCreatedAt, false);
            case "-created_at" -> comparing(ConversationEntity::getCreatedAt, true);
            case "updated_at" -> comparing(ConversationEntity::getUpdatedAt, false);
            default -> comparing(ConversationEntity::getUpdatedAt, true);
        };
    }

    private static Comparator<ConversationEntity> comparing(
            java.util.function.Function<ConversationEntity, java.time.LocalDateTime> timestamp,
            boolean descending) {
        Comparator<java.time.LocalDateTime> order = descending
                ? Comparator.reverseOrder()
                : Comparator.naturalOrder();
        return Comparator.comparing(timestamp, Comparator.nullsLast(order));
    }

}
