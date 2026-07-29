package io.github.aigoodle.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.agent.entity.AgentMessageEntity;
import io.github.aigoodle.agent.entity.ConversationEntity;
import io.github.aigoodle.agent.mapper.AgentMessageMapper;
import io.github.aigoodle.agent.service.ConversationService;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.web.dto.dify.DifyConversationVO;
import io.github.aigoodle.web.dto.dify.DifyMessageVO;
import io.github.aigoodle.web.dto.dify.DifyPage;
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

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dify 兼容的"聊天记录"读取接口 —— 补齐 {@link ChatController#chatMessages} 之后
 * 前端 {@code useDifyChat} 依赖的三条支线：
 *
 * <ul>
 *   <li>{@code GET  /conversations} — 拉当前应用下的会话列表（含游标翻页）</li>
 *   <li>{@code GET  /messages}      — 拉某会话下的历史消息，一行 = 一问一答</li>
 *   <li>{@code DELETE /conversations/{id}} — 删除单条会话（同时清空该会话消息）</li>
 *   <li>{@code POST /conversations/{id}/name} — 重命名/自动生成标题</li>
 * </ul>
 *
 * <p>路径全部落在 {@code ${spring-agent.web.base-path:}} 下，与 Dify 官方
 * {@code /v1/conversations} / {@code /v1/messages} 完全对齐，antd-react-chat 通过
 * vite 代理把 {@code /chatapi/*} 转发到这里即可无缝换后端。</p>
 *
 * <p><b>appId 解析</b>与 {@link ChatController} 保持一致，来源优先级：
 * {@code ?appId=} 查询参数 → {@code X-App-Id} 请求头 → {@code Authorization: Bearer <appId>}。
 * 三处都缺才抛 {@code missing_app_id}。</p>
 *
 * <p><b>降级</b>：{@code @ConditionalOnBean(ConversationService.class)} 保证只在 agent
 * 模块入场时注册；没有 agent 模块的部署（例如纯 workflow 场景）看不到这些端点。</p>
 */
@RestController
@ConditionalOnBean(ConversationService.class)
@RequestMapping("${spring-agent.web.base-path:}")
public class DifyChatHistoryController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final ConversationService conversationService;
    private final AgentMessageMapper messageMapper;

    public DifyChatHistoryController(ConversationService conversationService,
                                     AgentMessageMapper messageMapper) {
        this.conversationService = conversationService;
        this.messageMapper = messageMapper;
    }

    // ==================================================================== conversations

    /**
     * Dify {@code GET /conversations} —— 会话列表分页。
     * <p>
     * 参数：{@code user}（前端 identity，用于水平隔离；本实现暂不做严格过滤，与 chat-messages
     * 落库口径一致）、{@code last_id}（游标，返回其之后的记录）、{@code limit}（1–100，默认 20）、
     * {@code sort_by}（{@code created_at}/{@code -created_at}/{@code updated_at}/{@code -updated_at}
     * 四选一，默认 {@code -updated_at}）。
     */
    @GetMapping("/conversations")
    public DifyPage<DifyConversationVO> listConversations(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = "X-App-Id", required = false) String appIdHeader,
            @RequestParam(value = "appId", required = false) String appIdParam,
            @RequestParam(value = "user", required = false) String user,
            @RequestParam(value = "last_id", required = false) String lastId,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "sort_by", required = false) String sortBy) {
        String appId = resolveAppId(appIdParam, appIdHeader, auth);
        int cap = resolveLimit(limit);

        List<ConversationEntity> rows = conversationService.listByApp(appId);
        // listByApp 已按 pinned desc / updated_at desc 排；这里再按 sort_by 覆盖排序，
        // 保证与 Dify 语义一致（pinned 不作为额外排序键，除非将来 Dify 也引入 pinned）。
        rows.sort(comparatorFor(sortBy));

        int startIdx = 0;
        if (lastId != null && !lastId.isBlank()) {
            for (int i = 0; i < rows.size(); i++) {
                if (lastId.equals(rows.get(i).getId())) {
                    startIdx = i + 1;
                    break;
                }
            }
        }

        int endIdx = Math.min(rows.size(), startIdx + cap);
        boolean hasMore = endIdx < rows.size();
        List<DifyConversationVO> data = new ArrayList<>(endIdx - startIdx);
        for (int i = startIdx; i < endIdx; i++) {
            data.add(toConversationVO(rows.get(i)));
        }
        return new DifyPage<>(cap, hasMore, data);
    }

    /**
     * Dify {@code DELETE /conversations/{conversation_id}} —— 删除会话。请求体
     * 允许附带 {@code {"user":"..."}}（antd-react-chat 就是这么发的），但本实现不做
     * user 归属校验：删除权与前端持有 apiKey 一致，与 chat-messages 侧对齐。
     */
    @DeleteMapping("/conversations/{conversationId}")
    public Map<String, Object> deleteConversation(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = "X-App-Id", required = false) String appIdHeader,
            @RequestParam(value = "appId", required = false) String appIdParam,
            @PathVariable String conversationId,
            @RequestBody(required = false) Map<String, Object> body) {
        // 触发 appId 解析以维持鉴权入口一致；解析失败会 400，避免匿名删除。
        resolveAppId(appIdParam, appIdHeader, auth);
        conversationService.delete(conversationId);
        // Dify 返回 {"result":"success"}
        Map<String, Object> out = new HashMap<>();
        out.put("result", "success");
        return out;
    }

    /**
     * Dify {@code POST /conversations/{conversation_id}/name} —— 重命名会话。
     * <p>请求体：{@code {"name":"新标题","auto_generate":false}}。当 {@code auto_generate=true}
     * 时 Dify 会用 LLM 生成标题；本实现暂用第一条 USER 消息截断当作占位（后续可换成真正
     * 的 LLM 摘要）。</p>
     */
    @PostMapping("/conversations/{conversationId}/name")
    public DifyConversationVO renameConversation(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = "X-App-Id", required = false) String appIdHeader,
            @RequestParam(value = "appId", required = false) String appIdParam,
            @PathVariable String conversationId,
            @RequestBody(required = false) Map<String, Object> body) {
        resolveAppId(appIdParam, appIdHeader, auth);
        String name = null;
        boolean autoGenerate = false;
        if (body != null) {
            Object rawName = body.get("name");
            name = rawName == null ? null : trimToNull(rawName.toString());
            autoGenerate = Boolean.TRUE.equals(body.get("auto_generate"));
        }
        if (name == null && autoGenerate) {
            name = autoGenerateName(conversationId);
        }
        ConversationEntity updated = name == null
                ? conversationService.require(conversationId)
                : conversationService.rename(conversationId, name);
        return toConversationVO(updated);
    }

    // ==================================================================== messages

    /**
     * Dify {@code GET /messages} —— 拉某会话的历史消息，一行 = 一问一答。
     * <p>
     * 后端存储把 USER / ASSISTANT 分成两行（{@link AgentMessageEntity}），这里在读时
     * 按 {@code seq} 升序扫一遍配对：遇到 USER 缓存为 pending query，遇到 ASSISTANT 弹出
     * 配对成一条完整 Dify message row；未配对的孤立 USER / ASSISTANT 也会出一条只有
     * query 或 answer 的记录，避免历史断层。
     * <p>
     * {@code first_id} 语义参考 Dify：返回严格早于该 id 的记录；缺省时返回最近 N 条。
     * 响应按 {@code created_at} 升序返回（Dify 官方也是升序），前端 {@code loadDifyHistory}
     * 会再按此顺序渲染成对话流。
     */
    @GetMapping("/messages")
    public DifyPage<DifyMessageVO> listMessages(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = "X-App-Id", required = false) String appIdHeader,
            @RequestParam(value = "appId", required = false) String appIdParam,
            @RequestParam(value = "user", required = false) String user,
            @RequestParam("conversation_id") String conversationId,
            @RequestParam(value = "first_id", required = false) String firstId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        resolveAppId(appIdParam, appIdHeader, auth);
        int cap = resolveLimit(limit);

        List<AgentMessageEntity> rows = messageMapper.selectList(new LambdaQueryWrapper<AgentMessageEntity>()
                .eq(AgentMessageEntity::getConversationId, conversationId)
                .orderByAsc(AgentMessageEntity::getSeq));

        // seq 升序 → 顺序配对成 (query, answer)。id 用 ASSISTANT 的 message id，
        // 因为 Dify 里 message 主体就是助手回复；无 assistant 时退到 user id。
        List<DifyMessageVO> paired = pairIntoMessages(conversationId, rows);

        // 首先按 created_at 升序（老 → 新），再按 first_id 截断"这条之前"，
        // 最后取尾部 cap 条 —— 这样最新的 cap 条永远在尾部，与 Dify 一致。
        paired.sort(Comparator.comparingLong(m -> m.getCreatedAt() == null ? 0L : m.getCreatedAt()));

        if (firstId != null && !firstId.isBlank()) {
            int cutIdx = -1;
            for (int i = 0; i < paired.size(); i++) {
                if (firstId.equals(paired.get(i).getId())) {
                    cutIdx = i;
                    break;
                }
            }
            if (cutIdx >= 0) {
                paired = new ArrayList<>(paired.subList(0, cutIdx));
            }
        }

        boolean hasMore = paired.size() > cap;
        List<DifyMessageVO> data;
        if (hasMore) {
            data = new ArrayList<>(paired.subList(paired.size() - cap, paired.size()));
        } else {
            data = paired;
        }
        return new DifyPage<>(cap, hasMore, data);
    }

    // ==================================================================== helpers

    private static int resolveLimit(Integer raw) {
        if (raw == null || raw <= 0) return DEFAULT_LIMIT;
        return Math.min(MAX_LIMIT, raw);
    }

    private static Comparator<ConversationEntity> comparatorFor(String sortBy) {
        String s = sortBy == null ? "-updated_at" : sortBy.trim().toLowerCase();
        Comparator<ConversationEntity> cmp = switch (s) {
            case "created_at" -> Comparator.comparing(ConversationEntity::getCreatedAt,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "-created_at" -> Comparator.comparing(ConversationEntity::getCreatedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));
            case "updated_at" -> Comparator.comparing(ConversationEntity::getUpdatedAt,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            default -> Comparator.comparing(ConversationEntity::getUpdatedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));
        };
        return cmp;
    }

    private static DifyConversationVO toConversationVO(ConversationEntity row) {
        DifyConversationVO vo = new DifyConversationVO();
        vo.setId(row.getId());
        vo.setName(row.getName());
        vo.setInputs(Collections.emptyMap());
        vo.setStatus(row.getStatus() == null ? "normal" : row.getStatus());
        vo.setIntroduction(row.getIntroduction() == null ? "" : row.getIntroduction());
        vo.setCreatedAt(toEpochSecond(row.getCreatedAt()));
        vo.setUpdatedAt(toEpochSecond(row.getUpdatedAt() == null ? row.getCreatedAt() : row.getUpdatedAt()));
        return vo;
    }

    /**
     * 顺序扫描 seq 升序的消息行，把连续的 USER → ASSISTANT 配成一条 Dify message；
     * 支持中间夹 SYSTEM / TOOL 行（跳过），也支持只有 USER 没 ASSISTANT（response 还在跑
     * 或者 chat 失败）和只有 ASSISTANT 没 USER（少见，但兜底不丢数据）的场景。
     */
    private static List<DifyMessageVO> pairIntoMessages(String conversationId, List<AgentMessageEntity> rows) {
        List<DifyMessageVO> out = new ArrayList<>();
        AgentMessageEntity pendingUser = null;
        for (AgentMessageEntity row : rows) {
            String role = row.getRole() == null ? "" : row.getRole().toUpperCase();
            if ("USER".equals(role)) {
                // 前一条 USER 未配到 ASSISTANT 就直接落一条空 answer 的记录，避免丢
                if (pendingUser != null) {
                    out.add(buildPair(conversationId, pendingUser, null));
                }
                pendingUser = row;
            } else if ("ASSISTANT".equals(role)) {
                out.add(buildPair(conversationId, pendingUser, row));
                pendingUser = null;
            }
            // SYSTEM / TOOL 忽略：Dify 的消息模型只暴露 user query + assistant answer
        }
        if (pendingUser != null) {
            out.add(buildPair(conversationId, pendingUser, null));
        }
        return out;
    }

    private static DifyMessageVO buildPair(String conversationId,
                                           AgentMessageEntity userRow,
                                           AgentMessageEntity assistantRow) {
        DifyMessageVO vo = new DifyMessageVO();
        // id 优先取 ASSISTANT，方便前端把 message.id 与主体回复一一对应
        if (assistantRow != null) {
            vo.setId(assistantRow.getId());
            vo.setCreatedAt(toEpochSecond(assistantRow.getCreatedAt()));
        } else {
            vo.setId(userRow.getId());
            vo.setCreatedAt(toEpochSecond(userRow.getCreatedAt()));
        }
        vo.setConversationId(conversationId);
        vo.setInputs(Collections.emptyMap());
        vo.setQuery(userRow == null ? "" : safe(userRow.getContent()));
        vo.setAnswer(assistantRow == null ? "" : safe(assistantRow.getContent()));
        vo.setMessageFiles(Collections.emptyList());
        vo.setFeedback(null);
        vo.setRetrieverResources(Collections.emptyList());
        return vo;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static Long toEpochSecond(LocalDateTime t) {
        return t == null ? 0L : t.toEpochSecond(ZoneOffset.UTC);
    }

    private String autoGenerateName(String conversationId) {
        List<AgentMessageEntity> rows = messageMapper.selectList(new LambdaQueryWrapper<AgentMessageEntity>()
                .eq(AgentMessageEntity::getConversationId, conversationId)
                .orderByAsc(AgentMessageEntity::getSeq)
                .last("limit 20"));
        for (AgentMessageEntity r : rows) {
            if ("USER".equalsIgnoreCase(r.getRole()) && r.getContent() != null && !r.getContent().isBlank()) {
                String s = r.getContent().trim();
                return s.length() > 60 ? s.substring(0, 60) + "…" : s;
            }
        }
        return null;
    }

    // ================================================ appId resolver（与 ChatController 对齐口径）

    /**
     * 三层解析：{@code ?appId=} → {@code X-App-Id} → {@code Authorization: Bearer <appId>}。
     * 与 {@link ChatController#chatMessages} 相比省略了 body 兜底 —— 这里都是 GET/DELETE
     * 场景，不方便走 body。三处全空抛 {@code missing_app_id}。
     */
    private static String resolveAppId(String queryParam, String headerParam, String authHeader) {
        String v = trimToNull(queryParam);
        if (v != null) return v;
        v = trimToNull(headerParam);
        if (v != null) return v;
        v = extractBearerToken(authHeader);
        if (v != null) return v;
        throw new AgentException("missing_app_id",
                "无法识别目标应用：请通过 ?appId= / X-App-Id / Authorization: Bearer 之一提供",
                null);
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
}
