package io.github.aigoodle.trigger.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.trigger.api.InvocationStatus;
import io.github.aigoodle.trigger.api.TriggerType;
import io.github.aigoodle.trigger.dispatch.DispatchResult;
import io.github.aigoodle.trigger.dispatch.TriggerDispatcherRegistry;
import io.github.aigoodle.trigger.entity.TriggerEntity;
import io.github.aigoodle.trigger.entity.TriggerInvocationEntity;
import io.github.aigoodle.trigger.mapper.TriggerInvocationMapper;
import io.github.aigoodle.trigger.mapper.TriggerMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Manages triggers and fires them — synchronously or asynchronously — recording every
 * invocation for history and replay. Decoupled from what runs via
 * {@link TriggerDispatcherRegistry}.
 */
public class TriggerService {

    private static final Logger log = LoggerFactory.getLogger(TriggerService.class);

    private final TriggerMapper triggerMapper;
    private final TriggerInvocationMapper invocationMapper;
    private final TriggerDispatcherRegistry dispatcherRegistry;
    private final Executor executor;
    private final List<TriggerChangeListener> changeListeners;

    public TriggerService(TriggerMapper triggerMapper, TriggerInvocationMapper invocationMapper,
                          TriggerDispatcherRegistry dispatcherRegistry, Executor executor,
                          List<TriggerChangeListener> changeListeners) {
        this.triggerMapper = triggerMapper;
        this.invocationMapper = invocationMapper;
        this.dispatcherRegistry = dispatcherRegistry;
        this.executor = executor;
        this.changeListeners = changeListeners == null ? List.of() : changeListeners;
    }

    // ------------------------------------------------------------------ CRUD

    @Transactional
    public TriggerEntity create(CreateTriggerRequest req) {
        TriggerEntity entity = new TriggerEntity();
        entity.setTenantId(req.getTenantId());
        entity.setName(req.getName());
        entity.setType(req.getType());
        entity.setEnabled(req.isEnabled());
        entity.setTargetType(req.getTargetType());
        entity.setTargetId(req.getTargetId());
        entity.setConfigJson(JsonUtils.toJson(req.getConfig()));
        triggerMapper.insert(entity);
        notifySaved(entity);
        return entity;
    }

    @Transactional
    public void setEnabled(String id, boolean enabled) {
        TriggerEntity entity = require(id);
        entity.setEnabled(enabled);
        triggerMapper.updateById(entity);
        if (enabled) {
            notifySaved(entity);
        } else {
            changeListeners.forEach(l -> l.onRemoved(id));
        }
    }

    @Transactional
    public void delete(String id) {
        triggerMapper.deleteById(id);
        changeListeners.forEach(l -> l.onRemoved(id));
    }

    public TriggerEntity require(String id) {
        TriggerEntity entity = triggerMapper.selectById(id);
        if (entity == null) {
            throw new AgentException("trigger_not_found", "Trigger not found: " + id, null);
        }
        return entity;
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
        return JsonUtils.parseMap(trigger.getConfigJson());
    }

    /** Find an enabled webhook trigger by its configured {@code path}. */
    public java.util.Optional<TriggerEntity> findWebhook(String path) {
        return listEnabledByType(TriggerType.WEBHOOK).stream()
                .filter(t -> path != null && path.equals(String.valueOf(config(t).get("path"))))
                .findFirst();
    }

    // --------------------------------------------------------------- firing

    /** Fire synchronously and return the dispatch result. */
    public DispatchResult fireSync(String triggerId, Map<String, Object> payload, String source) {
        TriggerEntity trigger = requireEnabled(triggerId);
        TriggerInvocationEntity inv = openInvocation(trigger.getId(), source, payload, null);
        return execute(trigger, inv, payload);
    }

    /** Fire asynchronously; returns the invocation id immediately. */
    public String fire(String triggerId, Map<String, Object> payload, String source) {
        TriggerEntity trigger = requireEnabled(triggerId);
        TriggerInvocationEntity inv = openInvocation(trigger.getId(), source, payload, null);
        inv.setStatus(InvocationStatus.PENDING);
        invocationMapper.updateById(inv);
        executor.execute(() -> {
            try {
                execute(trigger, inv, payload);
            } catch (Exception e) {
                log.error("Async trigger {} failed: {}", triggerId, e.getMessage(), e);
            }
        });
        return inv.getId();
    }

    /** Re-run a past invocation with its original payload (new invocation, linked via replayOf). */
    public TriggerInvocationEntity replay(String invocationId) {
        TriggerInvocationEntity original = invocationMapper.selectById(invocationId);
        if (original == null) {
            throw new AgentException("invocation_not_found", "Invocation not found: " + invocationId, null);
        }
        TriggerEntity trigger = require(original.getTriggerId());
        Map<String, Object> payload = JsonUtils.parseMap(original.getPayloadJson());
        TriggerInvocationEntity inv = openInvocation(trigger.getId(), "replay", payload, original.getId());
        execute(trigger, inv, payload);
        return invocationMapper.selectById(inv.getId());
    }

    public TriggerInvocationEntity invocation(String id) {
        return invocationMapper.selectById(id);
    }

    public List<TriggerInvocationEntity> invocations(String triggerId) {
        return invocationMapper.selectList(new LambdaQueryWrapper<TriggerInvocationEntity>()
                .eq(TriggerInvocationEntity::getTriggerId, triggerId)
                .orderByDesc(TriggerInvocationEntity::getCreatedAt));
    }

    // --------------------------------------------------------------- helpers

    private DispatchResult execute(TriggerEntity trigger, TriggerInvocationEntity inv, Map<String, Object> payload) {
        inv.setStatus(InvocationStatus.RUNNING);
        invocationMapper.updateById(inv);
        try {
            DispatchResult result = dispatcherRegistry.get(trigger.getTargetType())
                    .dispatch(trigger.getTargetId(), payload, inv.getId());
            inv.setStatus(result.isSuccess() ? InvocationStatus.COMPLETED : InvocationStatus.FAILED);
            inv.setRunId(result.getRunId());
            inv.setOutputsJson(JsonUtils.toJson(result.getOutputs()));
            inv.setError(result.getError());
            invocationMapper.updateById(inv);
            return result;
        } catch (Exception e) {
            log.error("Trigger {} dispatch failed: {}", trigger.getId(), e.getMessage(), e);
            inv.setStatus(InvocationStatus.FAILED);
            inv.setError(e.getMessage());
            invocationMapper.updateById(inv);
            return DispatchResult.failed(e.getMessage());
        }
    }

    private TriggerInvocationEntity openInvocation(String triggerId, String source,
                                                  Map<String, Object> payload, String replayOf) {
        TriggerInvocationEntity inv = new TriggerInvocationEntity();
        inv.setTriggerId(triggerId);
        inv.setSource(source);
        inv.setStatus(InvocationStatus.PENDING);
        inv.setPayloadJson(JsonUtils.toJson(payload));
        inv.setReplayOf(replayOf);
        invocationMapper.insert(inv);
        return inv;
    }

    private TriggerEntity requireEnabled(String triggerId) {
        TriggerEntity trigger = require(triggerId);
        if (Boolean.FALSE.equals(trigger.getEnabled())) {
            throw new AgentException("trigger_disabled", "Trigger is disabled: " + triggerId, null);
        }
        return trigger;
    }

    private void notifySaved(TriggerEntity entity) {
        if (Boolean.TRUE.equals(entity.getEnabled())) {
            changeListeners.forEach(l -> l.onSaved(entity));
        }
    }
}
