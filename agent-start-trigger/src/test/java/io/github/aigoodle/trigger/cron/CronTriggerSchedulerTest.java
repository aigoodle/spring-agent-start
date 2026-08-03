package io.github.aigoodle.trigger.cron;

import io.github.aigoodle.trigger.api.TriggerType;
import io.github.aigoodle.trigger.entity.TriggerEntity;
import io.github.aigoodle.trigger.service.TriggerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CronTriggerSchedulerTest {

    @Test
    void doesNotRegisterTaskRejectedByScheduler() {
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        TriggerService triggerService = mock(TriggerService.class);
        CronTriggerScheduler scheduler = scheduler(taskScheduler, triggerService);
        TriggerEntity cronTrigger = trigger("daily-report", TriggerType.CRON);
        when(triggerService.config(cronTrigger)).thenReturn(Map.of("expression", "0 0 * * * *"));
        when(taskScheduler.schedule(any(Runnable.class), any(Trigger.class))).thenReturn(null);

        scheduler.onSaved(cronTrigger);

        assertThat(scheduler.isScheduled(cronTrigger.getId())).isFalse();
    }

    @Test
    void cancelsExistingTaskWhenTriggerIsNoLongerCron() {
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        TriggerService triggerService = mock(TriggerService.class);
        ScheduledFuture<?> scheduledTask = mock(ScheduledFuture.class);
        CronTriggerScheduler scheduler = scheduler(taskScheduler, triggerService);
        TriggerEntity cronTrigger = trigger("inventory-sync", TriggerType.CRON);
        when(triggerService.config(cronTrigger)).thenReturn(Map.of("expression", "0 0 * * * *"));
        doReturn(scheduledTask).when(taskScheduler)
                .schedule(any(Runnable.class), any(Trigger.class));

        scheduler.onSaved(cronTrigger);
        scheduler.onSaved(trigger(cronTrigger.getId(), TriggerType.EVENT));

        assertThat(scheduler.isScheduled(cronTrigger.getId())).isFalse();
        verify(scheduledTask).cancel(false);
    }

    @SuppressWarnings("unchecked")
    private static CronTriggerScheduler scheduler(TaskScheduler taskScheduler,
                                                  TriggerService triggerService) {
        ObjectProvider<TriggerService> serviceProvider = mock(ObjectProvider.class);
        when(serviceProvider.getObject()).thenReturn(triggerService);
        return new CronTriggerScheduler(taskScheduler, serviceProvider);
    }

    private static TriggerEntity trigger(String triggerId, TriggerType type) {
        TriggerEntity trigger = new TriggerEntity();
        trigger.setId(triggerId);
        trigger.setType(type);
        return trigger;
    }
}
