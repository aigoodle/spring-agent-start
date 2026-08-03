package io.github.aigoodle.trigger.config;

import io.github.aigoodle.trigger.cron.CronTriggerScheduler;
import io.github.aigoodle.trigger.dispatch.TriggerDispatcher;
import io.github.aigoodle.trigger.dispatch.TriggerDispatcherRegistry;
import io.github.aigoodle.trigger.dispatch.WorkflowTriggerDispatcher;
import io.github.aigoodle.trigger.event.EventTriggerBus;
import io.github.aigoodle.trigger.mapper.TriggerInvocationMapper;
import io.github.aigoodle.trigger.mapper.TriggerMapper;
import io.github.aigoodle.trigger.service.TriggerChangeListener;
import io.github.aigoodle.trigger.service.TriggerInvocationRunner;
import io.github.aigoodle.trigger.service.TriggerService;
import io.github.aigoodle.trigger.web.TriggerWebhookController;
import io.github.aigoodle.workflow.config.SpringAgentWorkflowAutoConfiguration;
import io.github.aigoodle.workflow.service.WorkflowService;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Auto-configuration for triggers: the dispatcher registry (default workflow
 * dispatcher), the async executor, the cron scheduler, the event bus, the service, and
 * — in a web application — the inbound webhook controller.
 */
@AutoConfiguration(after = SpringAgentWorkflowAutoConfiguration.class)
@MapperScan("io.github.aigoodle.trigger.mapper")
public class SpringAgentTriggerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public WorkflowTriggerDispatcher workflowTriggerDispatcher(WorkflowService workflowService) {
        return new WorkflowTriggerDispatcher(workflowService);
    }

    @Bean
    @ConditionalOnMissingBean
    public TriggerDispatcherRegistry triggerDispatcherRegistry(List<TriggerDispatcher> dispatchers) {
        return new TriggerDispatcherRegistry(dispatchers);
    }

    @Bean(name = "triggerExecutor", destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "triggerExecutor")
    public ExecutorService triggerExecutor() {
        return Executors.newFixedThreadPool(4, task -> {
            Thread worker = new Thread(task, "agent-trigger");
            worker.setDaemon(true);
            return worker;
        });
    }

    @Bean(name = "triggerTaskScheduler")
    @ConditionalOnMissingBean(name = "triggerTaskScheduler")
    public TaskScheduler triggerTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("agent-cron-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        return scheduler;
    }

    @Bean
    @ConditionalOnMissingBean
    public CronTriggerScheduler cronTriggerScheduler(TaskScheduler triggerTaskScheduler,
                                                     ObjectProvider<TriggerService> triggerService) {
        return new CronTriggerScheduler(triggerTaskScheduler, triggerService);
    }

    @Bean
    @ConditionalOnMissingBean
    public EventTriggerBus eventTriggerBus(ObjectProvider<TriggerService> triggerService) {
        return new EventTriggerBus(triggerService);
    }

    @Bean
    @ConditionalOnMissingBean
    public TriggerInvocationRunner triggerInvocationRunner(
            TriggerInvocationMapper invocationMapper,
            TriggerDispatcherRegistry dispatcherRegistry) {
        return new TriggerInvocationRunner(invocationMapper, dispatcherRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public TriggerService triggerService(TriggerMapper triggerMapper,
                                         TriggerInvocationRunner invocationRunner,
                                         ExecutorService triggerExecutor,
                                         List<TriggerChangeListener> changeListeners) {
        return new TriggerService(triggerMapper, invocationRunner, triggerExecutor, changeListeners);
    }

    /** Webhook endpoint, only in a servlet web application. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(org.springframework.web.bind.annotation.RestController.class)
    static class WebhookConfiguration {
        @Bean
        @ConditionalOnMissingBean
        public TriggerWebhookController triggerWebhookController(TriggerService triggerService) {
            return new TriggerWebhookController(triggerService);
        }
    }
}
