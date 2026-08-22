package io.github.aigoodle.workflow;

import io.github.aigoodle.common.exception.PlatformException;
import io.github.aigoodle.workflow.engine.NodeExecutionStatus;
import io.github.aigoodle.workflow.engine.WorkflowExecutionEventType;
import io.github.aigoodle.workflow.engine.WorkflowRunStatus;
import io.github.aigoodle.workflow.engine.WorkflowEngine;
import io.github.aigoodle.workflow.engine.WorkflowRunOptions;
import io.github.aigoodle.workflow.engine.WorkflowRunResult;
import io.github.aigoodle.workflow.engine.RunCancellationToken;
import io.github.aigoodle.workflow.engine.NodeExecutorRegistry;
import io.github.aigoodle.workflow.entity.WorkflowCheckpointEntity;
import io.github.aigoodle.workflow.entity.WorkflowRunNodeEntity;
import io.github.aigoodle.workflow.service.WorkflowCheckpointStore;
import io.github.aigoodle.workflow.service.PersistentWorkflowRunner;
import io.github.aigoodle.workflow.graph.WorkflowGraph;
import io.github.aigoodle.workflow.graph.NodeDef;
import io.github.aigoodle.workflow.graph.NodeType;
import io.github.aigoodle.workflow.graph.EdgeDef;
import io.github.aigoodle.workflow.node.NodeExecutor;
import io.github.aigoodle.workflow.node.NodeResult;
import io.github.aigoodle.workflow.node.ExecutionContext;
import io.github.aigoodle.workflow.node.builtin.StartNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.EndNodeExecutor;
import io.github.aigoodle.workflow.node.builtin.HumanInputNodeExecutor;
import io.github.aigoodle.common.context.CurrentUser;
import io.github.aigoodle.common.context.UserContextHolder;
import io.github.aigoodle.workflow.service.WorkflowSignalResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = WorkflowTestApplication.class)
class WorkflowCheckpointStoreIntegrationTest {

    @Autowired
    private WorkflowCheckpointStore store;

    @Test
    void createsCheckpointNodesAndAdvancesWithCas() {
        String runId = UUID.randomUUID().toString();
        WorkflowCheckpointEntity checkpoint = checkpoint(runId);
        WorkflowRunNodeEntity node = node("start", NodeExecutionStatus.PENDING);
        store.create(checkpoint, List.of(node));

        assertEquals(0L, store.require("checkpoint-test", runId).getCheckpointVersion());
        assertEquals(1, store.nodes("checkpoint-test", runId).size());

        node.setStatus(NodeExecutionStatus.COMPLETED.name());
        node.setAttempt(1);
        checkpoint.setStatus(WorkflowRunStatus.RUNNING.name());
        long version = store.commitNode(checkpoint, 0, node, WorkflowExecutionEventType.NODE_COMPLETED);

        assertEquals(1L, version);
        assertEquals(1L, store.require("checkpoint-test", runId).getCheckpointVersion());
        assertEquals(NodeExecutionStatus.COMPLETED.name(),
                store.nodes("checkpoint-test", runId).getFirst().getStatus());
    }

    @Test
    void rejectsStaleCasAndRollsBackNodeMutation() {
        String runId = UUID.randomUUID().toString();
        WorkflowCheckpointEntity checkpoint = checkpoint(runId);
        WorkflowRunNodeEntity node = node("work", NodeExecutionStatus.PENDING);
        store.create(checkpoint, List.of(node));
        checkpoint.setStatus(WorkflowRunStatus.RUNNING.name());
        store.transition(checkpoint, 0, WorkflowExecutionEventType.RUN_RESUMED);

        node.setStatus(NodeExecutionStatus.COMPLETED.name());
        assertThrows(PlatformException.class, () ->
                store.commitNode(checkpoint, 0, node, WorkflowExecutionEventType.NODE_COMPLETED));

        assertEquals(NodeExecutionStatus.PENDING.name(),
                store.nodes("checkpoint-test", runId).getFirst().getStatus());
    }

    @Test
    void leasePreventsTwoInstancesFromAdvancingSameRun() {
        String runId = UUID.randomUUID().toString();
        store.create(checkpoint(runId), List.of());

        assertTrue(store.acquireLease("checkpoint-test", runId, "instance-a", Duration.ofSeconds(30)));
        assertFalse(store.acquireLease("checkpoint-test", runId, "instance-b", Duration.ofSeconds(30)));
        assertFalse(store.releaseLease("checkpoint-test", runId, "instance-b"));
        assertTrue(store.releaseLease("checkpoint-test", runId, "instance-a"));
        assertTrue(store.acquireLease("checkpoint-test", runId, "instance-b", Duration.ofSeconds(30)));
    }

    @Test
    void resumesFailedRunWithoutRepeatingCompletedNodesAndUsesPinnedGraph() {
        AtomicInteger workAttempts = new AtomicInteger();
        NodeExecutor flaky = new NodeExecutor() {
            public NodeType type() { return NodeType.TEMPLATE_TRANSFORM; }
            public NodeResult execute(NodeDef node, ExecutionContext context) {
                if (workAttempts.incrementAndGet() == 1) return NodeResult.failure("transient");
                return NodeResult.of("value", "recovered");
            }
        };
        WorkflowEngine engine = new WorkflowEngine(new NodeExecutorRegistry(List.of(
                new StartNodeExecutor(), new EndNodeExecutor(), flaky)));
        PersistentWorkflowRunner runner = new PersistentWorkflowRunner(engine, store);
        WorkflowGraph graph = new WorkflowGraph();
        graph.addNode(NodeDef.of("start", NodeType.START));
        graph.addNode(NodeDef.of("work", NodeType.TEMPLATE_TRANSFORM));
        graph.addNode(NodeDef.of("end", NodeType.END).with("outputs", Map.of("answer", "{{#work.value#}}")));
        graph.addEdge(EdgeDef.of("start", "work"));
        graph.addEdge(EdgeDef.of("work", "end"));
        String runId = UUID.randomUUID().toString();

        WorkflowRunResult failed = runner.start("checkpoint-test", "workflow-a", "version-7",
                graph, Map.of("input", "kept"), "conversation-a", options(runId));
        assertEquals(WorkflowRunStatus.FAILED, failed.getStatus());
        assertEquals("version-7", store.require("checkpoint-test", runId).getGraphVersion());

        // Mutating the caller's graph after launch cannot affect resume: graph JSON is pinned in the checkpoint.
        graph.getNodes().clear();
        WorkflowRunResult resumed = runner.resume("checkpoint-test", runId, options(runId));

        assertTrue(resumed.isSuccess(), resumed.getError());
        assertEquals("recovered", resumed.output("answer"));
        assertEquals(2, workAttempts.get());
        Map<String, WorkflowRunNodeEntity> byNode = store.nodes("checkpoint-test", runId).stream()
                .collect(java.util.stream.Collectors.toMap(WorkflowRunNodeEntity::getNodeId, value -> value));
        assertEquals(1, byNode.get("start").getAttempt(), "completed START must not execute again");
        assertEquals(2, byNode.get("work").getAttempt());
        assertEquals(WorkflowRunStatus.SUCCEEDED.name(), store.require("checkpoint-test", runId).getStatus());
    }

    @Test
    void humanInputWaitsWithoutThreadAndResumesAfterRestartWithIdempotentSignal() {
        WorkflowEngine engine = new WorkflowEngine(new NodeExecutorRegistry(List.of(
                new StartNodeExecutor(), new EndNodeExecutor(), new HumanInputNodeExecutor())));
        PersistentWorkflowRunner firstInstance = new PersistentWorkflowRunner(engine, store);
        WorkflowGraph graph = new WorkflowGraph();
        graph.addNode(NodeDef.of("start", NodeType.START));
        graph.addNode(NodeDef.of("human", NodeType.HUMAN_INPUT)
                .with("allowedUserIds", List.of("alice"))
                .with("inputSchema", Map.of(
                        "required", List.of("name"),
                        "properties", Map.of("name", Map.of("type", "string")))));
        graph.addNode(NodeDef.of("end", NodeType.END)
                .with("outputs", Map.of("answer", "{{#human.name#}}")));
        graph.addEdge(EdgeDef.of("start", "human"));
        graph.addEdge(EdgeDef.of("human", "end"));
        String runId = UUID.randomUUID().toString();

        long started = System.nanoTime();
        WorkflowRunResult waiting = firstInstance.start("checkpoint-test", "workflow-hitl", "v3",
                graph, Map.of(), null, options(runId));
        assertEquals(WorkflowRunStatus.WAITING, waiting.getStatus());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 1000,
                "waiting must release execution resources rather than block");
        assertNotNull(waiting.getWaitRequest().resumeToken());
        assertEquals(WorkflowRunStatus.WAITING.name(), store.require("checkpoint-test", runId).getStatus());
        assertNull(store.require("checkpoint-test", runId).getLeaseOwner());

        // A new runner instance simulates service restart; all wait state comes from the database.
        PersistentWorkflowRunner restarted = new PersistentWorkflowRunner(engine, store);
        String correlationKey = store.require("checkpoint-test", runId).getCorrelationKey();
        UserContextHolder.set(CurrentUser.builder().tenantId("checkpoint-test").userId("bob").build());
        assertThrows(PlatformException.class, () -> restarted.signalByCorrelation("checkpoint-test",
                correlationKey, waiting.getWaitRequest().resumeToken(), "forbidden-1",
                Map.of("name", "Mallory"), options(runId)));
        UserContextHolder.set(CurrentUser.builder().tenantId("checkpoint-test").userId("alice").build());
        try {
            assertThrows(PlatformException.class, () -> restarted.signalByCorrelation("checkpoint-test",
                    correlationKey, waiting.getWaitRequest().resumeToken(), "invalid-1",
                    Map.of("name", 42), options(runId)));
            WorkflowSignalResult resumed = restarted.signalByCorrelation("checkpoint-test", correlationKey,
                    waiting.getWaitRequest().resumeToken(), "callback-1", Map.of("name", "Ada"), options(runId));
            assertTrue(resumed.accepted());
            assertEquals("Ada", resumed.runResult().output("answer"));

            WorkflowSignalResult duplicate = restarted.signal("checkpoint-test", runId,
                    waiting.getWaitRequest().resumeToken(), "callback-1", Map.of("name", "Ada"), options(runId));
            assertFalse(duplicate.accepted());
            assertTrue(duplicate.duplicate());
        } finally {
            UserContextHolder.clear();
        }
        assertEquals(WorkflowRunStatus.SUCCEEDED.name(), store.require("checkpoint-test", runId).getStatus());
    }

    @Test
    void sleepUntilIsRecoveredFromDurableWaitWithoutAResumeToken() throws Exception {
        WorkflowEngine engine = new WorkflowEngine(new NodeExecutorRegistry(List.of(
                new StartNodeExecutor(), new EndNodeExecutor(), new io.github.aigoodle.workflow.node.builtin.SleepUntilNodeExecutor())));
        PersistentWorkflowRunner runner = new PersistentWorkflowRunner(engine, store);
        WorkflowGraph graph = new WorkflowGraph();
        graph.addNode(NodeDef.of("start", NodeType.START));
        graph.addNode(NodeDef.of("sleep", NodeType.SLEEP_UNTIL).with("delayMillis", 10));
        graph.addNode(NodeDef.of("end", NodeType.END).with("outputs", Map.of("wokeAt", "{{#sleep.wokeAt#}}")));
        graph.addEdge(EdgeDef.of("start", "sleep"));
        graph.addEdge(EdgeDef.of("sleep", "end"));
        String runId = UUID.randomUUID().toString();

        WorkflowRunResult waiting = runner.start("checkpoint-test", "workflow-sleep", "v1",
                graph, Map.of(), null, options(runId));
        assertEquals(WorkflowRunStatus.WAITING, waiting.getStatus());
        Thread.sleep(20);

        new io.github.aigoodle.workflow.service.WorkflowWaitRecoveryService(store,
                new PersistentWorkflowRunner(engine, store)).recoverDueWaits();
        WorkflowCheckpointEntity completed = store.require("checkpoint-test", runId);
        assertEquals(WorkflowRunStatus.SUCCEEDED.name(), completed.getStatus());
        assertNull(completed.getLeaseOwner());
    }

    @Test
    void waitingRunCanBeDurablyCancelledAndCannotBeResumed() {
        WorkflowEngine engine = new WorkflowEngine(new NodeExecutorRegistry(List.of(
                new StartNodeExecutor(), new EndNodeExecutor(), new HumanInputNodeExecutor())));
        PersistentWorkflowRunner runner = new PersistentWorkflowRunner(engine, store);
        WorkflowGraph graph = new WorkflowGraph();
        graph.addNode(NodeDef.of("start", NodeType.START));
        graph.addNode(NodeDef.of("human", NodeType.HUMAN_INPUT));
        graph.addNode(NodeDef.of("end", NodeType.END));
        graph.addEdge(EdgeDef.of("start", "human"));
        graph.addEdge(EdgeDef.of("human", "end"));
        String runId = UUID.randomUUID().toString();
        assertEquals(WorkflowRunStatus.WAITING, runner.start("checkpoint-test", "workflow-cancel-wait",
                "v1", graph, Map.of(), null, options(runId)).getStatus());

        assertTrue(runner.cancel("checkpoint-test", runId, "operator stopped it"));
        WorkflowCheckpointEntity cancelled = store.require("checkpoint-test", runId);
        assertEquals(WorkflowRunStatus.CANCELLED.name(), cancelled.getStatus());
        assertEquals("operator stopped it", cancelled.getInterruptReason());
        assertNull(cancelled.getResumeTokenHash());
        assertThrows(PlatformException.class, () -> runner.resume("checkpoint-test", runId, options(runId)));
    }

    @Test
    void expiredApprovalCanEscalateToAnotherRoleAndCorrelationKey() {
        WorkflowEngine engine = new WorkflowEngine(new NodeExecutorRegistry(List.of(
                new StartNodeExecutor(), new EndNodeExecutor(),
                new io.github.aigoodle.workflow.node.builtin.ApprovalNodeExecutor())));
        PersistentWorkflowRunner runner = new PersistentWorkflowRunner(engine, store);
        WorkflowGraph graph = new WorkflowGraph();
        graph.addNode(NodeDef.of("start", NodeType.START));
        graph.addNode(NodeDef.of("approval", NodeType.APPROVAL)
                .with("timeoutSeconds", 60).with("timeoutStrategy", "ESCALATE")
                .with("escalationCorrelationKey", "approval:managers")
                .with("allowedRoles", List.of("reviewer"))
                .with("escalationAllowedRoles", List.of("manager")));
        graph.addNode(NodeDef.of("end", NodeType.END));
        graph.addEdge(EdgeDef.of("start", "approval"));
        graph.addEdge(EdgeDef.of("approval", "end"));
        String runId = UUID.randomUUID().toString();
        WorkflowRunResult waiting = runner.start("checkpoint-test", "workflow-escalation", "v1",
                graph, Map.of(), null, options(runId));
        WorkflowCheckpointEntity expired = store.require("checkpoint-test", runId);
        expired.setWaitExpiresAt(java.time.LocalDateTime.now().minusSeconds(1));
        store.transition(expired, expired.getCheckpointVersion(), WorkflowExecutionEventType.RUN_WAITING);

        new io.github.aigoodle.workflow.service.WorkflowWaitRecoveryService(store, runner).recoverDueWaits();
        WorkflowCheckpointEntity escalated = store.require("checkpoint-test", runId);
        assertEquals(WorkflowRunStatus.WAITING.name(), escalated.getStatus());
        assertEquals("approval:managers", escalated.getCorrelationKey());
        assertNull(escalated.getWaitExpiresAt());

        UserContextHolder.set(CurrentUser.builder().tenantId("checkpoint-test").userId("manager-1")
                .roles(Set.of("manager")).build());
        try {
            assertTrue(runner.signalByCorrelation("checkpoint-test", "approval:managers",
                    waiting.getWaitRequest().resumeToken(), "manager-approved", Map.of("approved", true),
                    options(runId)).accepted());
        } finally {
            UserContextHolder.clear();
        }
        assertEquals(WorkflowRunStatus.SUCCEEDED.name(), store.require("checkpoint-test", runId).getStatus());
    }

    @Test
    void longRunningNodeRenewsLeaseAndCannotBeTakenOver() throws Exception {
        NodeExecutor slow = new NodeExecutor() {
            @Override public NodeType type() { return NodeType.TEMPLATE_TRANSFORM; }
            @Override public NodeResult execute(NodeDef node, ExecutionContext context) {
                try { Thread.sleep(700); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); return NodeResult.failure("interrupted"); }
                return NodeResult.of("value", "done");
            }
        };
        WorkflowEngine engine = new WorkflowEngine(new NodeExecutorRegistry(List.of(
                new StartNodeExecutor(), new EndNodeExecutor(), slow)));
        PersistentWorkflowRunner runner = new PersistentWorkflowRunner(engine, store,
                "lease-owner", Duration.ofMillis(300));
        WorkflowGraph graph = new WorkflowGraph();
        graph.addNode(NodeDef.of("start", NodeType.START));
        graph.addNode(NodeDef.of("slow", NodeType.TEMPLATE_TRANSFORM));
        graph.addNode(NodeDef.of("end", NodeType.END));
        graph.addEdge(EdgeDef.of("start", "slow"));
        graph.addEdge(EdgeDef.of("slow", "end"));
        String runId = UUID.randomUUID().toString();

        java.util.concurrent.CompletableFuture<WorkflowRunResult> execution =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> runner.start(
                        "checkpoint-test", "workflow-heartbeat", "v1", graph, Map.of(), null, options(runId)));
        Thread.sleep(450);
        assertFalse(store.acquireLease("checkpoint-test", runId, "takeover", Duration.ofSeconds(1)),
                "heartbeat must keep the lease from expiring during a long node");
        assertEquals(WorkflowRunStatus.SUCCEEDED, execution.get().getStatus());
    }

    @Test
    void activeRunCanBePausedDurablyAndResumed() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        NodeExecutor slow = new NodeExecutor() {
            @Override public NodeType type() { return NodeType.TEMPLATE_TRANSFORM; }
            @Override public NodeResult execute(NodeDef node, ExecutionContext context) {
                invocations.incrementAndGet();
                try { Thread.sleep(500); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); return NodeResult.failure("paused"); }
                return NodeResult.of("value", "done");
            }
        };
        WorkflowEngine engine = new WorkflowEngine(new NodeExecutorRegistry(List.of(
                new StartNodeExecutor(), new EndNodeExecutor(), slow)));
        PersistentWorkflowRunner runner = new PersistentWorkflowRunner(engine, store);
        WorkflowGraph graph = new WorkflowGraph();
        graph.addNode(NodeDef.of("start", NodeType.START));
        graph.addNode(NodeDef.of("slow", NodeType.TEMPLATE_TRANSFORM));
        graph.addNode(NodeDef.of("end", NodeType.END));
        graph.addEdge(EdgeDef.of("start", "slow"));
        graph.addEdge(EdgeDef.of("slow", "end"));
        String runId = UUID.randomUUID().toString();
        java.util.concurrent.CompletableFuture<WorkflowRunResult> execution =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> runner.start(
                        "checkpoint-test", "workflow-pause", "v1", graph, Map.of(), null, options(runId)));
        while (invocations.get() == 0) Thread.onSpinWait();

        assertTrue(runner.pause("checkpoint-test", runId, "maintenance"));
        assertEquals(WorkflowRunStatus.PAUSED, execution.get().getStatus());
        assertEquals(WorkflowRunStatus.PAUSED.name(), store.require("checkpoint-test", runId).getStatus());

        WorkflowRunResult resumed = runner.resume("checkpoint-test", runId, options(runId));
        assertEquals(WorkflowRunStatus.SUCCEEDED, resumed.getStatus());
        assertEquals(2, invocations.get(), "interrupted node must restart, completed nodes must not");
    }

    private static WorkflowRunOptions options(String runId) {
        return new WorkflowRunOptions(Duration.ofSeconds(5), Duration.ofSeconds(2), 4,
                new RunCancellationToken(), runId);
    }

    private static WorkflowCheckpointEntity checkpoint(String runId) {
        WorkflowCheckpointEntity value = new WorkflowCheckpointEntity();
        value.setTenantId("checkpoint-test");
        value.setRunId(runId);
        value.setWorkflowId("workflow-v1");
        value.setGraphVersion("v1");
        value.setGraphJson("{\"nodes\":[],\"edges\":[]}");
        value.setStatus(WorkflowRunStatus.RUNNING.name());
        value.setNodeStatesJson("{}");
        value.setVariablePoolJson("{}");
        value.setBranchResultsJson("{}");
        value.setIterationCursorsJson("{}");
        value.setPendingNodesJson("[]");
        return value;
    }

    private static WorkflowRunNodeEntity node(String nodeId, NodeExecutionStatus status) {
        WorkflowRunNodeEntity value = new WorkflowRunNodeEntity();
        value.setNodeId(nodeId);
        value.setNodeType("START");
        value.setStatus(status.name());
        value.setAttempt(0);
        return value;
    }
}
