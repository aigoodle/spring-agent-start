package io.github.aigoodle.trigger;

import io.github.aigoodle.trigger.api.InvocationStatus;
import io.github.aigoodle.trigger.api.TriggerType;
import io.github.aigoodle.trigger.cron.CronTriggerScheduler;
import io.github.aigoodle.trigger.dispatch.DispatchResult;
import io.github.aigoodle.trigger.entity.TriggerEntity;
import io.github.aigoodle.trigger.entity.TriggerInvocationEntity;
import io.github.aigoodle.trigger.event.EventTriggerBus;
import io.github.aigoodle.trigger.service.CreateTriggerRequest;
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
        WorkflowGraph g = new WorkflowGraph();
        g.addNode(NodeDef.of("start", NodeType.START));
        g.addNode(NodeDef.of("tpl", NodeType.TEMPLATE_TRANSFORM)
                .with("template", "Echo: {{#sys.text#}}").with("outputKey", "msg"));
        g.addNode(NodeDef.of("end", NodeType.END).with("outputs", Map.of("answer", "{{#tpl.msg#}}")));
        g.addEdge(EdgeDef.of("start", "tpl"));
        g.addEdge(EdgeDef.of("tpl", "end"));
        WorkflowEntity wf = workflowService.save(
                "app-trigger-" + java.util.UUID.randomUUID(),
                "t", "echo", "workflow", g);
        return wf.getId();
    }

    private TriggerEntity trigger(TriggerType type, Map<String, Object> config) {
        return triggerService.create(CreateTriggerRequest.builder()
                .tenantId("t").name(type + "-trigger").type(type)
                .targetType("workflow").targetId(echoWorkflow())
                .config(config).enabled(true).build());
    }

    @Test
    void webhookFiresWorkflowSynchronously() {
        TriggerEntity t = trigger(TriggerType.WEBHOOK, Map.of("path", "hook1", "token", "s3cr3t"));
        assertTrue(triggerService.findWebhook("hook1").isPresent());

        DispatchResult r = triggerService.fireSync(t.getId(), Map.of("text", "hi"), "webhook");
        assertTrue(r.isSuccess(), r.getError());
        assertEquals("Echo: hi", r.getOutputs().get("answer"));

        List<TriggerInvocationEntity> invs = triggerService.invocations(t.getId());
        assertEquals(1, invs.size());
        assertEquals(InvocationStatus.COMPLETED, invs.get(0).getStatus());
        assertNotNull(invs.get(0).getRunId());
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
        TriggerEntity t = trigger(TriggerType.MANUAL, Map.of());
        String invId = triggerService.fire(t.getId(), Map.of("text", "async"), "manual");
        assertNotNull(invId);

        TriggerInvocationEntity inv = await(() -> {
            TriggerInvocationEntity i = triggerService.invocation(invId);
            return i != null && i.getStatus() == InvocationStatus.COMPLETED ? i : null;
        }, 5000);
        assertNotNull(inv, "async invocation should complete");
        assertEquals("{\"answer\":\"Echo: async\"}", inv.getOutputsJson());
    }

    @Test
    void replayReusesOriginalPayload() {
        TriggerEntity t = trigger(TriggerType.MANUAL, Map.of());
        DispatchResult first = triggerService.fireSync(t.getId(), Map.of("text", "replay-me"), "manual");
        String origId = triggerService.invocations(t.getId()).get(0).getId();

        TriggerInvocationEntity replayed = triggerService.replay(origId);
        assertEquals(InvocationStatus.COMPLETED, replayed.getStatus());
        assertEquals(origId, replayed.getReplayOf());
        assertEquals("Echo: replay-me", first.getOutputs().get("answer"));
        assertTrue(replayed.getOutputsJson().contains("Echo: replay-me"));
    }

    @Test
    void cronTriggerSchedulesAndFires() {
        TriggerEntity t = trigger(TriggerType.CRON, Map.of("expression", "*/1 * * * * *"));
        assertTrue(cronScheduler.isScheduled(t.getId()), "cron trigger should be scheduled on create");

        List<TriggerInvocationEntity> invs = await(() -> {
            List<TriggerInvocationEntity> list = triggerService.invocations(t.getId());
            return list.isEmpty() ? null : list;
        }, 6000);
        assertNotNull(invs, "cron trigger should fire within a few seconds");
        assertTrue(invs.stream().anyMatch(i -> "cron".equals(i.getSource())));
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
