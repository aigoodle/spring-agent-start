package io.github.aigoodle.trigger.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.common.exception.PlatformException;
import io.github.aigoodle.common.context.UserContextHolder;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.trigger.api.TriggerType;
import io.github.aigoodle.trigger.cron.TriggerSchedule;
import io.github.aigoodle.trigger.dispatch.DispatchResult;
import io.github.aigoodle.trigger.entity.TriggerEntity;
import io.github.aigoodle.trigger.entity.TriggerInvocationEntity;
import io.github.aigoodle.workflow.entity.WorkflowEntity;
import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.service.WorkflowService;
import io.github.aigoodle.trigger.mapper.TriggerMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;

/**
 * Manages triggers and fires them — synchronously or asynchronously — recording every
 * invocation for history and replay. Decoupled from what runs via
 * {@link TriggerDispatcherRegistry}.
 */
public class TriggerService {

    private static final Logger logger = LoggerFactory.getLogger(TriggerService.class);

    private final TriggerMapper triggerMapper;
    private final TriggerInvocationRunner invocationRunner;
    private final Executor executor;
    private final List<TriggerChangeListener> changeListeners;

    public TriggerService(TriggerMapper triggerMapper, TriggerInvocationRunner invocationRunner,
                          Executor executor,
                          List<TriggerChangeListener> changeListeners) {
        this.triggerMapper = triggerMapper;
        this.invocationRunner = invocationRunner;
        this.executor = executor;
        this.changeListeners = changeListeners == null ? List.of() : List.copyOf(changeListeners);
    }

    // ------------------------------------------------------------------ CRUD

    @Transactional
    public TriggerEntity create(CreateTriggerRequest request) {
        TriggerEntity trigger = newTrigger(request);
        triggerMapper.insert(trigger);
        notifySaved(trigger);
        return trigger;
    }

    @Transactional
    public void setEnabled(String triggerId, boolean enabled) {
        TriggerEntity trigger = require(triggerId);
        trigger.setEnabled(enabled);
        if (trigger.getType() == TriggerType.CRON) {
            trigger.setNextFireAt(enabled
                    ? TriggerSchedule.from(config(trigger)).firstFireAt(LocalDateTime.now())
                    : null);
            trigger.setLockOwner(null);
            trigger.setLockUntil(null);
        }
        triggerMapper.updateById(trigger);
        if (enabled) {
            notifySaved(trigger);
        } else {
            changeListeners.forEach(listener -> listener.onRemoved(triggerId));
        }
    }

    @Transactional
    public void delete(String triggerId) {
        triggerMapper.deleteById(triggerId);
        changeListeners.forEach(listener -> listener.onRemoved(triggerId));
    }

    public TriggerEntity require(String triggerId) {
        TriggerEntity trigger = triggerMapper.selectById(triggerId);
        if (trigger == null) {
            throw new PlatformException("trigger_not_found", "Trigger not found: " + triggerId, null);
        }
        return trigger;
    }

    public List<TriggerEntity> list(String tenantId) {
        return triggerMapper.selectList(new LambdaQueryWrapper<TriggerEntity>()
                .eq(TriggerEntity::getTenantId, tenantId == null ? "default" : tenantId));
    }

    public List<TriggerEntity> listEnabledByType(TriggerType type) {
        return triggerMapper.selectList(new LambdaQueryWrapper<TriggerEntity>()
                .eq(TriggerEntity::getType, type)
                .eq(TriggerEntity::getEnabled, true));
    }

    public Map<String, Object> config(TriggerEntity trigger) {
        Map<String, Object> parsedConfig = JsonUtils.parseMap(trigger.getConfigJson());
        return parsedConfig == null ? Map.of() : parsedConfig;
    }

    /** Find an enabled webhook trigger by its configured {@code path}. */
    public java.util.Optional<TriggerEntity> findWebhook(String path) {
        return listEnabledByType(TriggerType.WEBHOOK).stream()
                .filter(trigger -> path != null
                        && path.equals(String.valueOf(config(trigger).get("path"))))
                .findFirst();
    }

    // --------------------------------------------------------------- firing

    /** Fire synchronously and return the dispatch result. */
    public DispatchResult fireSynchronously(TriggerInvocationRequest request) {
        TriggerEntity trigger = requireEnabled(request.triggerId());
        TriggerInvocationEntity invocation = invocationRunner.open(
                InvocationDraft.initial(request), trigger.getTenantId());
        return invocationRunner.execute(trigger, invocation, request.payload());
    }

    /** All workflow schedules owned by one authenticated user, used as LLM deletion candidates. */
    public List<TriggerEntity> listUserSchedules(String tenantId, String userId) {
        if (userId == null || userId.isBlank()) return List.of();
        return triggerMapper.selectList(new LambdaQueryWrapper<TriggerEntity>()
                .eq(TriggerEntity::getTenantId, tenantId == null ? "default" : tenantId)
                .eq(TriggerEntity::getUserId, userId)
                .eq(TriggerEntity::getType, TriggerType.CRON)
                .eq(TriggerEntity::getTargetType, "workflow")
                .orderByDesc(TriggerEntity::getCreatedAt));
    }

    /** Deletes only ids that are still owned by the supplied tenant/user. */
    @Transactional
    public List<TriggerEntity> deleteOwnedSchedules(String tenantId, String userId,
                                                    List<String> triggerIds) {
        if (userId == null || userId.isBlank() || triggerIds == null || triggerIds.isEmpty()) {
            return List.of();
        }
        List<String> distinctIds = triggerIds.stream()
                .filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (distinctIds.isEmpty()) return List.of();
        List<TriggerEntity> owned = triggerMapper.selectList(new LambdaQueryWrapper<TriggerEntity>()
                .in(TriggerEntity::getId, distinctIds)
                .eq(TriggerEntity::getTenantId, tenantId == null ? "default" : tenantId)
                .eq(TriggerEntity::getUserId, userId)
                .eq(TriggerEntity::getType, TriggerType.CRON)
                .eq(TriggerEntity::getTargetType, "workflow"));
        List<TriggerEntity> deleted = new ArrayList<>();
        owned.forEach(trigger -> {
            if (triggerMapper.deleteById(trigger.getId()) == 1) {
                deleted.add(trigger);
                changeListeners.forEach(listener -> listener.onRemoved(trigger.getId()));
            }
        });
        return List.copyOf(deleted);
    }

    /** Enables or pauses only workflow schedules owned by the supplied tenant/user. */
    @Transactional
    public List<TriggerEntity> setOwnedSchedulesEnabled(String tenantId, String userId,
                                                        List<String> triggerIds, boolean enabled) {
        List<TriggerEntity> owned = ownedSchedules(tenantId, userId, triggerIds);
        owned.forEach(trigger -> {
            trigger.setEnabled(enabled);
            trigger.setNextFireAt(enabled
                    ? TriggerSchedule.from(config(trigger)).firstFireAt(LocalDateTime.now()) : null);
            trigger.setLockOwner(null);
            trigger.setLockUntil(null);
            triggerMapper.updateById(trigger);
            if (enabled) notifySaved(trigger);
            else changeListeners.forEach(listener -> listener.onRemoved(trigger.getId()));
        });
        return List.copyOf(owned);
    }

    /** Starts an immediate asynchronous invocation for owned, enabled workflow schedules. */
    public Map<TriggerEntity, String> runOwnedSchedulesNow(String tenantId, String userId,
                                                           List<String> triggerIds) {
        Map<TriggerEntity, String> started = new java.util.LinkedHashMap<>();
        ownedSchedules(tenantId, userId, triggerIds).stream()
                .filter(trigger -> Boolean.TRUE.equals(trigger.getEnabled()))
                .forEach(trigger -> {
                    Map<String, Object> triggerConfig = config(trigger);
                    Object rawData = triggerConfig.get("data");
                    Map<String, Object> data = rawData instanceof Map<?, ?> map
                            ? map.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                            entry -> String.valueOf(entry.getKey()), Map.Entry::getValue)) : Map.of();
                    String conversationId = text(triggerConfig.get("conversationId"));
                    String invocationId = fireAsynchronously(TriggerInvocationRequest.scheduled(
                            trigger.getId(), data, conversationId));
                    started.put(trigger, invocationId);
                });
        return Map.copyOf(started);
    }

    private List<TriggerEntity> ownedSchedules(String tenantId, String userId,
                                                List<String> triggerIds) {
        if (userId == null || userId.isBlank() || triggerIds == null || triggerIds.isEmpty()) {
            return List.of();
        }
        List<String> distinctIds = triggerIds.stream()
                .filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (distinctIds.isEmpty()) return List.of();
        return triggerMapper.selectList(new LambdaQueryWrapper<TriggerEntity>()
                .in(TriggerEntity::getId, distinctIds)
                .eq(TriggerEntity::getTenantId, tenantId == null ? "default" : tenantId)
                .eq(TriggerEntity::getUserId, userId)
                .eq(TriggerEntity::getType, TriggerType.CRON)
                .eq(TriggerEntity::getTargetType, "workflow"));
    }

    /** Updates one workflow schedule only when it is still owned by the supplied tenant/user. */
    @Transactional
    public TriggerEntity updateOwnedSchedule(String tenantId, String userId, String triggerId,
                                             String name, String targetWorkflowId,
                                             Map<String, Object> scheduleConfig) {
        if (userId == null || userId.isBlank() || triggerId == null || triggerId.isBlank()) {
            throw new PlatformException("schedule_update_forbidden",
                    "Scheduled task is not available for update", null);
        }
        TriggerEntity trigger = triggerMapper.selectOne(new LambdaQueryWrapper<TriggerEntity>()
                .eq(TriggerEntity::getId, triggerId)
                .eq(TriggerEntity::getTenantId, tenantId == null ? "default" : tenantId)
                .eq(TriggerEntity::getUserId, userId)
                .eq(TriggerEntity::getType, TriggerType.CRON)
                .eq(TriggerEntity::getTargetType, "workflow")
                .last("LIMIT 1"));
        if (trigger == null) {
            throw new PlatformException("schedule_update_forbidden",
                    "Scheduled task is not available for update", null);
        }
        Map<String, Object> normalizedConfig = scheduleConfig == null
                ? Map.of() : new java.util.LinkedHashMap<>(scheduleConfig);
        trigger.setName(name == null || name.isBlank() ? trigger.getName() : name.trim());
        if (targetWorkflowId != null && !targetWorkflowId.isBlank()) {
            trigger.setTargetId(targetWorkflowId);
        }
        trigger.setConfigJson(JsonUtils.toJson(normalizedConfig));
        trigger.setEnabled(true);
        trigger.setNextFireAt(TriggerSchedule.from(normalizedConfig).firstFireAt(LocalDateTime.now()));
        trigger.setLockOwner(null);
        trigger.setLockUntil(null);
        triggerMapper.updateById(trigger);
        notifySaved(trigger);
        return trigger;
    }

    /** Reconciles the published workflow's START-node schedule into one durable trigger. */
    @Transactional
    public TriggerEntity syncPublishedWorkflowSchedule(WorkflowEntity workflow,
                                                        WorkflowService workflowService) {
        String sourceKey = workflow.getAppId() == null ? workflow.getId() : workflow.getAppId();
        list(workflow.getTenantId()).stream()
                .filter(existing -> sourceKey.equals(config(existing).get("sourceWorkflowKey")))
                .forEach(existing -> delete(existing.getId()));
        NodeDef start;
        try {
            start = workflowService.graphOf(workflow).getNodes().stream()
                    .filter(node -> node.getType() == io.github.aigoodle.workflow.graph.NodeType.START)
                    .findFirst().orElse(null);
        } catch (PlatformException invalidGraph) {
            // Publishing an empty/legacy draft must not fail merely because it has no schedule.
            return null;
        }
        if (start == null) return null;
        Map<String, Object> data = start.getData() == null ? Map.of() : start.getData();
        Object rawTrigger = data.get("triggers");
        Map<String, Object> designer = rawTrigger instanceof Map<?, ?> map
                ? map.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        entry -> String.valueOf(entry.getKey()), Map.Entry::getValue))
                : Map.of();
        if (!Boolean.TRUE.equals(data.get("triggersEnabled"))
                || !"schedule".equalsIgnoreCase(String.valueOf(designer.get("type")))) {
            return null;
        }
        Map<String, Object> config = new java.util.LinkedHashMap<>();
        copy(designer, config, "scheduleType", "expression", "runAt", "timeZone", "conversationId");
        config.put("sourceWorkflowKey", sourceKey);
        Object payloadJson = designer.get("payloadJson");
        config.put("data", payloadJson == null ? Map.of() : JsonUtils.parseMap(String.valueOf(payloadJson)));
        String selectedTarget = text(designer.get("targetWorkflowId"));
        return create(CreateTriggerRequest.builder()
                .tenantId(workflow.getTenantId())
                .userId(UserContextHolder.currentUserId())
                .name(text(designer.get("name")) == null ? workflow.getName() + " schedule" : text(designer.get("name")))
                .type(TriggerType.CRON)
                .targetType("workflow")
                .targetId(selectedTarget == null ? workflow.getId() : selectedTarget)
                .config(config)
                .enabled(true)
                .build());
    }

    /** Atomically leases due rows; safe when every cluster node calls it concurrently. */
    @Transactional
    public List<TriggerEntity> claimDueSchedules(String owner, Duration lease, int limit) {
        LocalDateTime now = LocalDateTime.now();
        List<TriggerEntity> due = triggerMapper.selectList(
                new LambdaQueryWrapper<TriggerEntity>()
                        .eq(TriggerEntity::getType, TriggerType.CRON)
                        .eq(TriggerEntity::getEnabled, true)
                        .isNotNull(TriggerEntity::getNextFireAt)
                        .le(TriggerEntity::getNextFireAt, now)
                        .orderByAsc(TriggerEntity::getNextFireAt)
                        .last("LIMIT " + Math.max(1, Math.min(limit, 200))));
        List<TriggerEntity> claimed = new ArrayList<>();
        for (TriggerEntity candidate : due) {
            if (triggerMapper.tryClaim(candidate.getId(), owner, now, now.plus(lease)) != 1) continue;
            TriggerEntity trigger = require(candidate.getId());
            TriggerSchedule schedule = TriggerSchedule.from(config(trigger));
            trigger.setLastFireAt(trigger.getNextFireAt());
            trigger.setFireCount((trigger.getFireCount() == null ? 0L : trigger.getFireCount()) + 1L);
            trigger.setNextFireAt(schedule.nextAfter(trigger.getNextFireAt()));
            if (schedule.oneTime()) trigger.setEnabled(false);
            // The cursor is advanced before dispatch, so an expired lease cannot duplicate this occurrence.
            trigger.setLockOwner(null);
            trigger.setLockUntil(null);
            triggerMapper.updateById(trigger);
            claimed.add(trigger);
        }
        return claimed;
    }

    /** Fire asynchronously; returns the invocation id immediately. */
    public String fireAsynchronously(TriggerInvocationRequest request) {
        TriggerEntity trigger = requireEnabled(request.triggerId());
        TriggerInvocationEntity invocation = invocationRunner.open(
                InvocationDraft.initial(request), trigger.getTenantId());
        executor.execute(() -> {
            try {
                invocationRunner.execute(trigger, invocation, request.payload());
            } catch (Exception exception) {
                // Persistence infrastructure failures can still escape the runner.
                logger.error("Async trigger {} failed: {}",
                        request.triggerId(), exception.getMessage(), exception);
            }
        });
        return invocation.getId();
    }

    /** Dispatches a row already claimed by the database scheduler (including disabled one-shot rows). */
    public String fireClaimedAsynchronously(TriggerEntity trigger) {
        Map<String, Object> config = config(trigger);
        Object configuredData = config.get("data") == null ? config.get("payload") : config.get("data");
        Map<String, Object> payload = configuredData instanceof Map<?, ?> configured
                ? configured.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        entry -> String.valueOf(entry.getKey()), Map.Entry::getValue))
                : Map.of();
        String conversationId = config.get("conversationId") == null
                ? null : String.valueOf(config.get("conversationId"));
        TriggerInvocationRequest request = TriggerInvocationRequest.scheduled(
                trigger.getId(), payload, conversationId);
        TriggerInvocationEntity invocation = invocationRunner.open(
                InvocationDraft.initial(request), trigger.getTenantId());
        executor.execute(() -> invocationRunner.execute(trigger, invocation, payload));
        return invocation.getId();
    }

    /** @deprecated Use {@link #fireSynchronously(TriggerInvocationRequest)}. */
    @Deprecated(forRemoval = false)
    public DispatchResult fireSync(String triggerId, Map<String, Object> payload, String source) {
        return fireSynchronously(new TriggerInvocationRequest(triggerId, payload, source, null));
    }

    /** @deprecated Use {@link #fireAsynchronously(TriggerInvocationRequest)}. */
    @Deprecated(forRemoval = false)
    public String fire(String triggerId, Map<String, Object> payload, String source) {
        return fireAsynchronously(new TriggerInvocationRequest(triggerId, payload, source, null));
    }

    /** Re-run a past invocation with its original payload (new invocation, linked via replayOf). */
    public TriggerInvocationEntity replay(String invocationId) {
        TriggerInvocationEntity original = invocationRunner.find(invocationId);
        if (original == null) {
            throw new PlatformException("invocation_not_found", "Invocation not found: " + invocationId, null);
        }
        TriggerEntity trigger = require(original.getTriggerId());
        Map<String, Object> payload = JsonUtils.parseMap(original.getPayloadJson());
        TriggerInvocationEntity replay = invocationRunner.open(
                InvocationDraft.replay(original, payload), trigger.getTenantId());
        invocationRunner.execute(trigger, replay, payload);
        return invocationRunner.find(replay.getId());
    }

    public TriggerInvocationEntity invocation(String id) {
        return invocationRunner.find(id);
    }

    public List<TriggerInvocationEntity> invocations(String triggerId) {
        return invocationRunner.listForTrigger(triggerId);
    }

    private TriggerEntity requireEnabled(String triggerId) {
        TriggerEntity trigger = require(triggerId);
        if (!Boolean.TRUE.equals(trigger.getEnabled())) {
            throw new PlatformException("trigger_disabled", "Trigger is disabled: " + triggerId, null);
        }
        return trigger;
    }

    private void notifySaved(TriggerEntity trigger) {
        if (Boolean.TRUE.equals(trigger.getEnabled())) {
            changeListeners.forEach(listener -> listener.onSaved(trigger));
        }
    }

    private TriggerEntity newTrigger(CreateTriggerRequest request) {
        TriggerEntity trigger = new TriggerEntity();
        trigger.setTenantId(request.getTenantId());
        trigger.setUserId(request.getUserId() == null
                ? UserContextHolder.currentUserId() : request.getUserId());
        trigger.setName(request.getName());
        trigger.setType(request.getType());
        trigger.setEnabled(request.isEnabled());
        trigger.setTargetType(request.getTargetType());
        trigger.setTargetId(request.getTargetId());
        trigger.setConfigJson(JsonUtils.toJson(request.getConfig()));
        trigger.setFireCount(0L);
        if (trigger.getType() == TriggerType.CRON && trigger.getEnabled()) {
            trigger.setNextFireAt(TriggerSchedule.from(request.getConfig())
                    .firstFireAt(LocalDateTime.now()));
        }
        return trigger;
    }

    private static void copy(Map<String, Object> source, Map<String, Object> target, String... keys) {
        for (String key : keys) if (source.get(key) != null) target.put(key, source.get(key));
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }
}
