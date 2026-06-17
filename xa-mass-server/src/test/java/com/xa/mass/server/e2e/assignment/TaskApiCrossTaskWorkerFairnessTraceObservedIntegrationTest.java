package com.xa.mass.server.e2e.assignment;

import com.google.gson.JsonObject;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractTraceObservedE2eTest;
import com.xa.mass.server.testutil.WsFrameTestSupport;
import com.xa.mass.trace.operator.TraceAnalyzeRequest;
import com.xa.mass.trace.operator.TraceAnalyzeResponse;
import com.xa.mass.trace.operator.TraceOperatorService;
import com.xa.mass.workerpack.sample.client.SampleWorkerWebSocketClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=false",
                "mass.mock.data.workers=mock/test_mock_workers_empty.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json",
                "mass.trace.sink.enabled=true",
                "mass.trace.sink.queue-capacity=512",
                "mass.trace.sink.rotate-after-lines=1",
                "mass.trace.sink.overflow-policy=FALLBACK_SYNC",
                "mass.trace.sink.shutdown-drain-timeout-ms=1500"
        }
)
@ActiveProfiles("memory-local")
@DirtiesContext
class TaskApiCrossTaskWorkerFairnessTraceObservedIntegrationTest extends AbstractTraceObservedE2eTest {

    private static final String WORKER_GROUP_ID = "fairness-pool";
    private static final int WEBSOCKET_PORT = findFreePort();
    private static final Path TRACE_OUTPUT_DIR = traceOutputDir("cross-task-worker-fairness-trace-observed");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
        registerTraceOutputDir(registry, TRACE_OUTPUT_DIR);
    }

    @Test
    void bulkBudgetLeavesWorkerCapacityForInteractiveTaskAndTraceProvesFairness() throws Exception {
        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        List<ManualAckWebSocketClient> clients = new ArrayList<>();
        try {
            for (int i = 0; i < 21; i++) {
                String workerId = "fairness-worker-" + i;
                registerSdkStatelessWorkerWithAttributes(
                        workerId,
                        WORKER_GROUP_ID,
                        "demoApp",
                        Map.of("routingTags", "shared,us")
                );
                ManualAckWebSocketClient client =
                        new ManualAckWebSocketClient(wsUri, WORKER_GROUP_ID, workerId,
                                canonicalWorkerRouteKey(WORKER_GROUP_ID, workerId));
                clients.add(client);
                assertClientConnects(client, "fairness worker client failed to connect: " + workerId);
            }
            assertTrue(awaitCondition(() -> clients.stream().allMatch(client -> app.isWorkerReachable(client.getWorkerId())),
                            20,
                            100L),
                    "all fairness workers must become transport-online before assignment");

            String bulkTaskId = createTaskId(
                    "cross-task-fairness-bulk",
                    "bulk pressure task",
                    targets("bulk-target-", 100),
                    1,
                    "BULK",
                    WORKER_GROUP_ID
            );
            String interactiveTaskId = createTaskId(
                    "cross-task-fairness-interactive",
                    "interactive task under bulk pressure",
                    List.of("interactive-target"),
                    1,
                    "INTERACTIVE",
                    WORKER_GROUP_ID
            );

            assertApiOk(approveTask(bulkTaskId));
            List<JsonObject> bulkDispatches = awaitDispatchesForTargetPrefix(clients, "bulk-target-", 20, 8, TimeUnit.SECONDS);
            Set<String> bulkWorkers = workerIds(bulkDispatches);
            assertEquals(20, bulkDispatches.size());
            assertEquals(20, bulkWorkers.size());
            waitForRuntimeTaskSnapshot(bulkTaskId,
                    snapshot -> "RUNNING".equals(snapshot.task().get("status"))
                            && snapshot.activeLeases().size() == 20
                            && snapshot.stats().readyCount() == 80,
                    "bulk task capped at 20 active workers with remaining ready backlog",
                    20,
                    250L);

            assertApiOk(approveTask(interactiveTaskId));
            JsonObject interactiveDispatch = awaitFirstDispatchForTarget(clients, "interactive-target", 5, TimeUnit.SECONDS);
            assertNotNull(interactiveDispatch, "interactive task should dispatch while bulk backlog remains active");
            String interactiveWorker = receivedWorkerId(interactiveDispatch);
            assertFalse(bulkWorkers.contains(interactiveWorker),
                    "interactive task should use worker capacity left outside the bulk budget");
            waitForRuntimeTaskSnapshot(interactiveTaskId,
                    snapshot -> "RUNNING".equals(snapshot.task().get("status"))
                            && snapshot.activeLeases().size() == 1,
                    "interactive task RUNNING with active lease",
                    20,
                    250L);

            TraceAnalyzeResponse trace = awaitTraceScenarioOk(new TraceOperatorService(), bulkTaskId, interactiveTaskId);
            assertTrue(trace.ok(), trace.issues().toString());
            assertEquals("cross-task-worker-fairness", trace.scenarioId());
            assertTrue(trace.eventTypeCounts().getOrDefault("ASSIGNMENT_SUMMARY", 0L) >= 2L,
                    "trace should include assignment summaries for both tasks");

            sendSuccessFor(interactiveDispatch, clients, "interactive-fairness-ok");
            assertEquals("ALL_MESSAGES_SUCCEEDED",
                    waitForTerminalRuntimeTask(interactiveTaskId).task().get("terminalReason"));
        } finally {
            disconnectAll(clients);
        }
    }

    private void disconnectAll(List<ManualAckWebSocketClient> clients) {
        for (ManualAckWebSocketClient client : clients) {
            try {
                client.disconnect();
            } catch (Exception ignored) {
                // Best-effort cleanup after the test has collected its proof.
            }
        }
    }

    private List<String> targets(String prefix, int count) {
        List<String> targets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            targets.add(prefix + i);
        }
        return targets;
    }

    private Set<String> workerIds(List<JsonObject> dispatches) {
        Set<String> workerIds = new LinkedHashSet<>();
        for (JsonObject dispatch : dispatches) {
            workerIds.add(receivedWorkerId(dispatch));
        }
        return workerIds;
    }

    private List<JsonObject> awaitDispatchesForTargetPrefix(List<ManualAckWebSocketClient> clients,
                                                            String targetPrefix,
                                                            int expectedCount,
                                                            long timeout,
                                                            TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        List<JsonObject> dispatches = new ArrayList<>();
        while (System.nanoTime() < deadline && dispatches.size() < expectedCount) {
            for (ManualAckWebSocketClient client : clients) {
                JsonObject dispatch = client.pollTask(25, TimeUnit.MILLISECONDS);
                if (dispatch != null && target(dispatch).startsWith(targetPrefix)) {
                    dispatches.add(dispatch);
                    if (dispatches.size() == expectedCount) {
                        return dispatches;
                    }
                }
            }
        }
        return dispatches;
    }

    private JsonObject awaitFirstDispatchForTarget(List<ManualAckWebSocketClient> clients,
                                                   String expectedTarget,
                                                   long timeout,
                                                   TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            for (ManualAckWebSocketClient client : clients) {
                JsonObject dispatch = client.pollTask(25, TimeUnit.MILLISECONDS);
                if (dispatch != null && expectedTarget.equals(target(dispatch))) {
                    return dispatch;
                }
            }
        }
        return null;
    }

    private void sendSuccessFor(JsonObject dispatch,
                                List<ManualAckWebSocketClient> clients,
                                String detail) throws Exception {
        String workerId = receivedWorkerId(dispatch);
        for (ManualAckWebSocketClient client : clients) {
            if (client.getWorkerId().equals(workerId)) {
                client.sendSuccess(dispatch, detail);
                return;
            }
        }
        throw new AssertionError("No connected client for dispatched worker " + workerId);
    }

    private TraceAnalyzeResponse awaitTraceScenarioOk(TraceOperatorService traceOperator,
                                                      String bulkTaskId,
                                                      String interactiveTaskId)
            throws InterruptedException {
        return awaitTraceScenarioOk(TRACE_OUTPUT_DIR,
                "cross-task-worker-fairness",
                bulkTaskId + "," + interactiveTaskId);
    }

    private static String target(JsonObject dispatch) {
        JsonObject payload = WsFrameTestSupport.payload(dispatch);
        if (payload == null || !payload.has("target") || payload.get("target").isJsonNull()) {
            return "";
        }
        return payload.get("target").getAsString();
    }

    private static String receivedWorkerId(JsonObject dispatch) {
        return dispatch.get("_receivedByWorkerId").getAsString();
    }

    private static final class ManualAckWebSocketClient extends SampleWorkerWebSocketClient {
        private final BlockingQueue<JsonObject> taskQueue = new LinkedBlockingQueue<>();

        private ManualAckWebSocketClient(URI serverUri, String workerGroupId, String workerId, String routeKey) {
            super(com.xa.mass.server.e2e.support.AbstractSampleE2eTest.withWorkerRouteKey(serverUri, routeKey),
                    workerId,
                    workerGroupId);
        }

        @Override
        public void onMessage(String message) {
            try {
                JsonObject frame = WsFrameTestSupport.parse(message);
                if (frame != null && WsFrameTestSupport.isTask(frame) && !WsFrameTestSupport.isResponse(frame)) {
                    frame.addProperty("_receivedByWorkerId", getWorkerId());
                    taskQueue.offer(frame);
                    return;
                }
            } catch (Exception ignored) {
                // Fall through to the base client for non-task frames or malformed payloads.
            }
            super.onMessage(message);
        }

        private JsonObject pollTask(long timeout, TimeUnit unit) throws InterruptedException {
            return taskQueue.poll(timeout, unit);
        }

        private void sendSuccess(JsonObject taskMessage, String detail) throws Exception {
            sendMessage(WsFrameTestSupport.buildTaskResult(
                    WsFrameTestSupport.resultCorrelationRef(taskMessage),
                    "SUCCESS",
                    detail
            ));
        }
    }
}
