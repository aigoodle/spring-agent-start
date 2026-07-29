package io.github.aigoodle.trigger.cron;

import io.github.aigoodle.trigger.api.TriggerType;
import io.github.aigoodle.trigger.entity.TriggerEntity;
import io.github.aigoodle.trigger.service.TriggerChangeListener;
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

    private static final Logger log = LoggerFactory.getLogger(CronTriggerScheduler.class);

    private final TaskScheduler taskScheduler;
    private final ObjectProvider<TriggerService> triggerService;
    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    public CronTriggerScheduler(TaskScheduler taskScheduler, ObjectProvider<TriggerService> triggerService) {
        this.taskScheduler = taskScheduler;
        this.triggerService = triggerService;
    }

    @Override
    public void afterSingletonsInstantiated() {
        for (TriggerEntity t : triggerService.getObject().listEnabledByType(TriggerType.CRON)) {
            schedule(t);
        }
    }

    @Override
    public void onSaved(TriggerEntity trigger) {
        if (trigger.getType() == TriggerType.CRON) {
            schedule(trigger);
        }
    }

    @Override
    public void onRemoved(String triggerId) {
        cancel(triggerId);
    }

    private void schedule(TriggerEntity trigger) {
        cancel(trigger.getId());
        Map<String, Object> config = triggerService.getObject().config(trigger);
        Object expr = config.get("expression");
        if (expr == null || String.valueOf(expr).isBlank()) {
            log.warn("Cron trigger {} has no 'expression'; skipping", trigger.getId());
            return;
        }
        try {
            ScheduledFuture<?> future = taskScheduler.schedule(() -> {
                try {
                    triggerService.getObject().fire(trigger.getId(), Map.of(), "cron");
                } catch (Exception e) {
                    log.error("Cron trigger {} fire failed: {}", trigger.getId(), e.getMessage());
                }
            }, new CronTrigger(String.valueOf(expr)));
            futures.put(trigger.getId(), future);
            log.info("Scheduled cron trigger {} with expression '{}'", trigger.getId(), expr);
        } catch (Exception e) {
            log.error("Invalid cron expression '{}' for trigger {}: {}", expr, trigger.getId(), e.getMessage());
        }
    }

    private void cancel(String triggerId) {
        ScheduledFuture<?> future = futures.remove(triggerId);
        if (future != null) {
            future.cancel(false);
        }
    }

    public boolean isScheduled(String triggerId) {
        return futures.containsKey(triggerId);
    }
}
