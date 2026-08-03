package io.github.aigoodle.trigger.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.trigger.api.InvocationStatus;
import io.github.aigoodle.trigger.dispatch.DispatchResult;
import io.github.aigoodle.trigger.dispatch.TriggerDispatcherRegistry;
import io.github.aigoodle.trigger.entity.TriggerEntity;
import io.github.aigoodle.trigger.entity.TriggerInvocationEntity;
import io.github.aigoodle.trigger.mapper.TriggerInvocationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/** Owns the persistence lifecycle of one trigger invocation. */
public final class TriggerInvocationRunner {

    private static final Logger logger = LoggerFactory.getLogger(TriggerInvocationRunner.class);

    private final TriggerInvocationMapper invocationMapper;
    private final TriggerDispatcherRegistry dispatcherRegistry;

    public TriggerInvocationRunner(TriggerInvocationMapper invocationMapper,
                                   TriggerDispatcherRegistry dispatcherRegistry) {
        this.invocationMapper = invocationMapper;
        this.dispatcherRegistry = dispatcherRegistry;
    }

    TriggerInvocationEntity open(InvocationDraft invocationDraft) {
        TriggerInvocationEntity invocation = new TriggerInvocationEntity();
        invocation.setTriggerId(invocationDraft.triggerId());
        invocation.setSource(invocationDraft.source());
        invocation.setStatus(InvocationStatus.PENDING);
        invocation.setPayloadJson(JsonUtils.toJson(invocationDraft.payload()));
        invocation.setReplayOf(invocationDraft.replayedInvocationId());
        invocationMapper.insert(invocation);
        return invocation;
    }

    DispatchResult execute(TriggerEntity trigger,
                           TriggerInvocationEntity invocation,
                           Map<String, Object> payload) {
        invocation.markRunning();
        save(invocation);
        try {
            DispatchResult dispatchResult = dispatcherRegistry.get(trigger.getTargetType())
                    .dispatch(trigger.getTargetId(), payload, invocation.getId());
            recordResult(invocation, dispatchResult);
            return dispatchResult;
        } catch (RuntimeException dispatchFailure) {
            logger.error("Trigger {} dispatch failed: {}",
                    trigger.getId(), dispatchFailure.getMessage(), dispatchFailure);
            recordFailure(invocation, dispatchFailure);
            return DispatchResult.failed(dispatchFailure.getMessage());
        }
    }

    TriggerInvocationEntity find(String invocationId) {
        return invocationMapper.selectById(invocationId);
    }

    List<TriggerInvocationEntity> listForTrigger(String triggerId) {
        return invocationMapper.selectList(new LambdaQueryWrapper<TriggerInvocationEntity>()
                .eq(TriggerInvocationEntity::getTriggerId, triggerId)
                .orderByDesc(TriggerInvocationEntity::getCreatedAt));
    }

    private void recordResult(TriggerInvocationEntity invocation, DispatchResult dispatchResult) {
        if (dispatchResult.isSuccess()) {
            invocation.markCompleted(
                    dispatchResult.getRunId(), JsonUtils.toJson(dispatchResult.getOutputs()));
        } else {
            invocation.markFailed(
                    dispatchResult.getRunId(),
                    JsonUtils.toJson(dispatchResult.getOutputs()),
                    dispatchResult.getError());
        }
        save(invocation);
    }

    private void recordFailure(TriggerInvocationEntity invocation, RuntimeException dispatchFailure) {
        invocation.markFailed(dispatchFailure.getMessage());
        save(invocation);
    }

    private void save(TriggerInvocationEntity invocation) {
        invocationMapper.updateById(invocation);
    }
}
