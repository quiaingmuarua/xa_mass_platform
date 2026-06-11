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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                "mass.trace.sink.queue-capacity=256",
                "mass.trace.sink.rotate-after-lines=1",
                "mass.trace.sink.overflow-policy=FALLBACK_SYNC",
                "mass.trace.sink.shutdown-drain-timeout-ms=1500"
        }
)
@ActiveProfiles("memory-local")
@DirtiesContext
class TaskApiBackgroundWorkerSharingTraceObservedIntegrationTest extends AbstractTraceObservedE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final Path TRACE_OUTPUT_DIR = traceOutputDir("background-worker-sharing-trace-observed");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
        registerTraceOutputDir(registry, TRACE_OUTPUT_DIR);
    }

    @Test
    void backgroundTasksShareStatelessWorkerAndAreObservedThroughCanonicalTrace() throws Exception {
        String workerId = "background-sharing-worker-0";
        registerSdkStatelessWorker(workerId, "demoApp", 2);

        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        ManualAckWebSocketClient client =
                new ManualAckWebSocketClient(wsUri, workerId, canonicalWorkerRouteKey("us", workerId));
        try {
            assertClientConnects(client, "background sharing sample client failed to connect");
            assertTrue(awaitCondition(() -> app.isWorkerReachable(workerId), 20, 100L),
                    "worker must become transport-online before approval");

            String firstTaskId = createBackgroundTask("background-sharing-first", "first background task", "target-a");
            assertApiOk(approveTask(firstTaskId));
            JsonObject firstDispatch = client.awaitTask(3, TimeUnit.SECONDS);
            assertNotNull(firstDispatch, "first background task should dispatch to stateless worker");
            waitForRuntimeTaskSnapshot(firstTaskId,
                    snapshot -> "RUNNING".equals(snapshot.task().get("status"))
                            && !snapshot.activeLeases().isEmpty(),
                    "RUNNING with active lease",
                    20,
                    250L);

            String secondTaskId = createBackgroundTask("background-sharing-second", "second background task", "target-b");
            assertApiOk(approveTask(secondTaskId));
            JsonObject secondDispatch = client.awaitTask(3, TimeUnit.SECONDS);
            assertNotNull(secondDispatch, "second background task should share the same stateless worker");
            waitForRuntimeTaskSnapshot(secondTaskId,
                    snapshot -> "RUNNING".equals(snapshot.task().get("status"))
                            && !snapshot.activeLeases().isEmpty(),
                    "RUNNING with active lease",
                    20,
                    250L);

            TraceOperatorService traceOperator = new TraceOperatorService();
            TraceAnalyzeResponse response = awaitTraceScenarioOk(traceOperator, secondTaskId);
            assertTrue(response.eventTypeCounts().containsKey("WORKER_MATCH_ACCEPTED"),
                    "canonical trace must include background match evidence");
            assertFalse(response.eventTypeCounts().containsKey("WORKER_LOCK_ACQUIRED"),
                    "background assignment must not acquire a long-lived worker lock");

            client.sendSuccess(secondDispatch, "background-second-ok");
            client.sendSuccess(firstDispatch, "background-first-ok");
            assertEquals("ALL_MESSAGES_SUCCEEDED", waitForTerminalRuntimeTask(secondTaskId).task().get("terminalReason"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", waitForTerminalRuntimeTask(firstTaskId).task().get("terminalReason"));
        } finally {
            client.disconnect();
        }
    }

    private String createBackgroundTask(String sourceRef, String textContent, String target) {
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("project", "demoApp");
        createBody.put("sharedConfig", Map.of("textContent", textContent));
        createBody.put("userId", "itest");
        createBody.put("sourceRef", sourceRef);
        createBody.put("executionSpec", Map.of("batchSize", 1, "foreground", false));

        Map<String, Object> createResponse = createTaskShell(createBody);
        assertApiOk(createResponse);
        String taskId = String.valueOf(responseData(createResponse).get("taskId"));
        assertFalse(taskId.isBlank());
        assertApiOk(appendTaskItems(taskId, "demo.dispatch", List.of(Map.of("target", target))));
        assertApiOk(sealTask(taskId));
        return taskId;
    }

    private TraceAnalyzeResponse awaitTraceScenarioOk(TraceOperatorService traceOperator, String taskId)
            throws InterruptedException {
        return awaitTraceScenarioOk(TRACE_OUTPUT_DIR, "background-worker-sharing", taskId);
    }

    private static final class ManualAckWebSocketClient extends SampleWorkerWebSocketClient {
        private final BlockingQueue<JsonObject> taskQueue = new LinkedBlockingQueue<>();

        private ManualAckWebSocketClient(URI serverUri, String workerId, String routeKey) {
            super(com.xa.mass.server.e2e.support.AbstractSampleE2eTest.withWorkerRouteKey(serverUri, routeKey), workerId);
        }

        @Override
        public void onMessage(String message) {
            try {
                JsonObject frame = WsFrameTestSupport.parse(message);
                if (frame != null && WsFrameTestSupport.isTask(frame) && !WsFrameTestSupport.isResponse(frame)) {
                    taskQueue.offer(frame);
                    return;
                }
            } catch (Exception ignored) {
                // Fall through to the base client for non-task frames or malformed payloads.
            }
            super.onMessage(message);
        }

        private JsonObject awaitTask(long timeout, TimeUnit unit) throws InterruptedException {
            return taskQueue.poll(timeout, unit);
        }

        private void sendSuccess(JsonObject taskMessage, String detail) throws Exception {
            sendMessage(WsFrameTestSupport.buildTaskResult(
                    WsFrameTestSupport.messageId(taskMessage),
                    WsFrameTestSupport.project(taskMessage),
                    getWorkerId(),
                    WsFrameTestSupport.taskId(taskMessage),
                    "SUCCESS",
                    detail
            ));
        }
    }
}
