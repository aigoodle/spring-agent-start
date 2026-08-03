package io.github.aigoodle.trigger.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.common.exception.AgentException;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.trigger.api.TriggerType;
import io.github.aigoodle.trigger.dispatch.DispatchResult;
import io.github.aigoodle.trigger.entity.TriggerEntity;
import io.github.aigoodle.trigger.entity.TriggerInvocationEntity;
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
            throw new AgentException("trigger_not_found", "Trigger not found: " + triggerId, null);
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
        TriggerInvocationEntity invocation = invocationRunner.open(InvocationDraft.initial(request));
        return invocationRunner.execute(trigger, invocation, request.payload());
    }

    /** Fire asynchronously; returns the invocation id immediately. */
    public String fireAsynchronously(TriggerInvocationRequest request) {
        TriggerEntity trigger = requireEnabled(request.triggerId());
        TriggerInvocationEntity invocation = invocationRunner.open(InvocationDraft.initial(request));
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

    /** @deprecated Use {@link #fireSynchronously(TriggerInvocationRequest)}. */
    @Deprecated(forRemoval = false)
    public DispatchResult fireSync(String triggerId, Map<String, Object> payload, String source) {
        return fireSynchronously(new TriggerInvocationRequest(triggerId, payload, source));
    }

    /** @deprecated Use {@link #fireAsynchronously(TriggerInvocationRequest)}. */
    @Deprecated(forRemoval = false)
    public String fire(String triggerId, Map<String, Object> payload, String source) {
        return fireAsynchronously(new TriggerInvocationRequest(triggerId, payload, source));
    }

    /** Re-run a past invocation with its original payload (new invocation, linked via replayOf). */
    public TriggerInvocationEntity replay(String invocationId) {
        TriggerInvocationEntity original = invocationRunner.find(invocationId);
        if (original == null) {
            throw new AgentException("invocation_not_found", "Invocation not found: " + invocationId, null);
        }
        TriggerEntity trigger = require(original.getTriggerId());
        Map<String, Object> payload = JsonUtils.parseMap(original.getPayloadJson());
        TriggerInvocationEntity replay = invocationRunner.open(InvocationDraft.replay(original, payload));
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
            throw new AgentException("trigger_disabled", "Trigger is disabled: " + triggerId, null);
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
        trigger.setName(request.getName());
        trigger.setType(request.getType());
        trigger.setEnabled(request.isEnabled());
        trigger.setTargetType(request.getTargetType());
        trigger.setTargetId(request.getTargetId());
        trigger.setConfigJson(JsonUtils.toJson(request.getConfig()));
        return trigger;
    }
}
