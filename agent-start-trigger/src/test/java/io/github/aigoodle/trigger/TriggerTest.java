package io.github.aigoodle.trigger;

import io.github.aigoodle.trigger.api.InvocationStatus;
import io.github.aigoodle.trigger.api.TriggerType;
import io.github.aigoodle.trigger.cron.CronTriggerScheduler;
import io.github.aigoodle.trigger.dispatch.DispatchResult;
import io.github.aigoodle.trigger.entity.TriggerEntity;
import io.github.aigoodle.trigger.entity.TriggerInvocationEntity;
import io.github.aigoodle.trigger.event.EventTriggerBus;
import io.github.aigoodle.trigger.service.CreateTriggerRequest;
import io.github.aigoodle.trigger.service.TriggerInvocationRequest;
import io.github.aigoodle.trigger.service.TriggerService;
import io.github.aigoodle.workflow.entity.WorkflowEntity;
import io.github.aigoodle.workflow.graph.EdgeDef;
import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.graph.WorkflowGraph;
import io.github.aigoodle.workflow.service.WorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = TriggerTestApplication.class)
class TriggerTest {

    @Autowired
    private WorkflowService workflowService;
    @Autowired
    private TriggerService triggerService;
    @Autowired
    private EventTriggerBus eventBus;
    @Autowired
    private CronTriggerScheduler cronScheduler;

    /** An echo workflow: Start -> Template("Echo: {{#sys.text#}}") -> End(answer). */
    private String echoWorkflow() {
        WorkflowGraph graph = new WorkflowGraph();
        graph.addNode(NodeDef.of("start", NodeType.START));
        graph.addNode(NodeDef.of("tpl", NodeType.TEMPLATE_TRANSFORM)
                .with("template", "Echo: {{#sys.text#}}").with("outputKey", "msg"));
        graph.addNode(NodeDef.of("end", NodeType.END)
                .with("outputs", Map.of("answer", "{{#tpl.msg#}}")));
        graph.addEdge(EdgeDef.of("start", "tpl"));
        graph.addEdge(EdgeDef.of("tpl", "end"));
        WorkflowEntity workflow = workflowService.save(
                "app-trigger-" + java.util.UUID.randomUUID(),
                "t", "echo", "workflow", graph);
        return workflow.getId();
    }

    private TriggerEntity trigger(TriggerType type, Map<String, Object> config) {
        return triggerService.create(CreateTriggerRequest.builder()
                .tenantId("t").name(type + "-trigger").type(type)
                .targetType("workflow").targetId(echoWorkflow())
                .config(config).enabled(true).build());
    }

    @Test
    void webhookFiresWorkflowSynchronously() {
        TriggerEntity trigger = trigger(
                TriggerType.WEBHOOK, Map.of("path", "hook1", "token", "s3cr3t"));
        assertTrue(triggerService.findWebhook("hook1").isPresent());

        DispatchResult result = triggerService.fireSynchronously(
                TriggerInvocationRequest.webhook(trigger.getId(), Map.of("text", "hi")));
        assertTrue(result.isSuccess(), result.getError());
        assertEquals("Echo: hi", result.getOutputs().get("answer"));

        List<TriggerInvocationEntity> invocations = triggerService.invocations(trigger.getId());
        assertEquals(1, invocations.size());
        assertEquals(InvocationStatus.COMPLETED, invocations.get(0).getStatus());
        assertNotNull(invocations.get(0).getRunId());
    }

    @Test
    void eventTriggerFiresOnPublish() {
        trigger(TriggerType.EVENT, Map.of("eventName", "order.created"));
        trigger(TriggerType.EVENT, Map.of("eventName", "order.cancelled")); // should not fire

        int fired = eventBus.publishSync("order.created", Map.of("text", "order-42"));
        assertEquals(1, fired);
    }

    @Test
    void asyncFireCompletesEventually() {
        TriggerEntity trigger = trigger(TriggerType.MANUAL, Map.of());
        String invocationId = triggerService.fireAsynchronously(
                TriggerInvocationRequest.manual(trigger.getId(), Map.of("text", "async")));
        assertNotNull(invocationId);

        TriggerInvocationEntity invocation = await(() -> {
            TriggerInvocationEntity candidate = triggerService.invocation(invocationId);
            return candidate != null && candidate.getStatus() == InvocationStatus.COMPLETED
                    ? candidate : null;
        }, 5000);
        assertNotNull(invocation, "async invocation should complete");
        assertEquals("{\"answer\":\"Echo: async\"}", invocation.getOutputsJson());
    }

    @Test
    void replayReusesOriginalPayload() {
        TriggerEntity trigger = trigger(TriggerType.MANUAL, Map.of());
        DispatchResult first = triggerService.fireSynchronously(
                TriggerInvocationRequest.manual(trigger.getId(), Map.of("text", "replay-me")));
        String originalInvocationId = triggerService.invocations(trigger.getId()).get(0).getId();

        TriggerInvocationEntity replayed = triggerService.replay(originalInvocationId);
        assertEquals(InvocationStatus.COMPLETED, replayed.getStatus());
        assertEquals(originalInvocationId, replayed.getReplayOf());
        assertEquals("Echo: replay-me", first.getOutputs().get("answer"));
        assertTrue(replayed.getOutputsJson().contains("Echo: replay-me"));
    }

    @Test
    void cronTriggerSchedulesAndFires() {
        TriggerEntity trigger = trigger(TriggerType.CRON, Map.of("expression", "*/1 * * * * *"));
        assertTrue(cronScheduler.isScheduled(trigger.getId()),
                "cron trigger should be scheduled on create");

        List<TriggerInvocationEntity> invocations = await(() -> {
            List<TriggerInvocationEntity> candidates = triggerService.invocations(trigger.getId());
            return candidates.isEmpty() ? null : candidates;
        }, 6000);
        assertNotNull(invocations, "cron trigger should fire within a few seconds");
        assertTrue(invocations.stream()
                .anyMatch(invocation -> "cron".equals(invocation.getSource())));
    }

    private <T> T await(Supplier<T> condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            T value = condition.get();
            if (value != null) {
                return value;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }
}
