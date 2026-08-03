package io.github.aigoodle.trigger.cron;

import io.github.aigoodle.trigger.api.TriggerType;
import io.github.aigoodle.trigger.entity.TriggerEntity;
import io.github.aigoodle.trigger.service.TriggerChangeListener;
import io.github.aigoodle.trigger.service.TriggerInvocationRequest;
import io.github.aigoodle.trigger.service.TriggerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Schedules CRON triggers on a {@link TaskScheduler}, (re)registering them as triggers
 * are created/enabled/removed. Uses an {@link ObjectProvider} for {@link TriggerService}
 * to avoid a construction-time dependency cycle (the service holds this as a listener).
 */
public class CronTriggerScheduler implements TriggerChangeListener, SmartInitializingSingleton {

    private static final Logger logger = LoggerFactory.getLogger(CronTriggerScheduler.class);

    private final TaskScheduler taskScheduler;
    private final ObjectProvider<TriggerService> triggerServiceProvider;
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    public CronTriggerScheduler(TaskScheduler taskScheduler,
                                ObjectProvider<TriggerService> triggerServiceProvider) {
        this.taskScheduler = taskScheduler;
        this.triggerServiceProvider = triggerServiceProvider;
    }

    @Override
    public void afterSingletonsInstantiated() {
        TriggerService triggerService = triggerServiceProvider.getObject();
        for (TriggerEntity cronTrigger : triggerService.listEnabledByType(TriggerType.CRON)) {
            scheduleTrigger(cronTrigger);
        }
    }

    @Override
    public void onSaved(TriggerEntity trigger) {
        if (trigger.getType() == TriggerType.CRON) {
            scheduleTrigger(trigger);
        } else {
            cancelScheduledTask(trigger.getId());
        }
    }

    @Override
    public void onRemoved(String triggerId) {
        cancelScheduledTask(triggerId);
    }

    private void scheduleTrigger(TriggerEntity trigger) {
        String triggerId = trigger.getId();
        cancelScheduledTask(triggerId);

        Map<String, Object> triggerConfig = triggerServiceProvider.getObject().config(trigger);
        Object configuredExpression = triggerConfig.get("expression");
        if (configuredExpression == null || String.valueOf(configuredExpression).isBlank()) {
            logger.warn("Cron trigger {} has no 'expression'; skipping", triggerId);
            return;
        }
        String cronExpression = String.valueOf(configuredExpression);
        try {
            ScheduledFuture<?> scheduledTask = taskScheduler.schedule(
                    () -> fireTrigger(triggerId), new CronTrigger(cronExpression));
            if (scheduledTask == null) {
                logger.warn("Task scheduler did not accept cron trigger {}", triggerId);
                return;
            }
            scheduledTasks.put(triggerId, scheduledTask);
            logger.info("Scheduled cron trigger {} with expression '{}'", triggerId, cronExpression);
        } catch (RuntimeException schedulingFailure) {
            logger.error("Unable to schedule cron expression '{}' for trigger {}: {}",
                    cronExpression, triggerId, schedulingFailure.getMessage(), schedulingFailure);
        }
    }

    private void fireTrigger(String triggerId) {
        try {
            triggerServiceProvider.getObject().fireAsynchronously(
                    TriggerInvocationRequest.cron(triggerId));
        } catch (RuntimeException invocationFailure) {
            logger.error("Cron trigger {} fire failed: {}",
                    triggerId, invocationFailure.getMessage(), invocationFailure);
        }
    }

    private void cancelScheduledTask(String triggerId) {
        ScheduledFuture<?> scheduledTask = scheduledTasks.remove(triggerId);
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
    }

    public boolean isScheduled(String triggerId) {
        return scheduledTasks.containsKey(triggerId);
    }
}
