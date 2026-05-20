package com.xa.mass.server.e2e.results;

import com.google.gson.JsonObject;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractTraceObservedE2eTest;
import com.xa.mass.server.e2e.support.ManualAckWebSocketWorkerClient;
import com.xa.mass.server.testutil.WsFrameTestSupport;
import com.xa.mass.trace.operator.TraceAnalyzeResponse;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
@ActiveProfiles("dev")
@DirtiesContext
public class TaskApiCallbackReplayTraceObservedIntegrationTest extends AbstractTraceObservedE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final Path TRACE_OUTPUT_DIR = traceOutputDir("callback-replay-trace-observed");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketPropertiesWithClientUri(registry, WEBSOCKET_PORT);
        registerTraceOutputDir(registry, TRACE_OUTPUT_DIR);
    }

    @Test
    void duplicateWebSocketCallbackReplayIsObservedThroughCanonicalTraceWithoutMutatingRuntimeFinality() throws Exception {
        String workerId = "callback-replay-worker";
        registerSdkWorkerWithContext(workerId, "us");

        URI uri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        ManualAckWebSocketWorkerClient client = new ManualAckWebSocketWorkerClient(uri, workerId);
        try {
            assertClientConnects(client, "callback replay worker failed to connect");

            Map<String, Object> createBody = new LinkedHashMap<>();
            createBody.put("project", "demoApp");
            createBody.put("sharedConfig", Map.of("textContent", "callback replay trace observed", "routingCode", "us"));
            createBody.put("userId", "itest");
            createBody.put("sourceRef", "callback-replay-trace");
            createBody.put("executionSpec", Map.of("batchSize", 1));

            Map<String, Object> createResponse = createTaskShell(createBody);
            assertApiOk(createResponse);
            String taskId = String.valueOf(responseData(createResponse).get("taskId"));
            assertApiOk(appendTaskItems(taskId, "demo.dispatch", List.of(Map.of("target", "target-a"))));
            assertApiOk(sealTask(taskId));
            assertApiOk(approveTask(taskId));

            JsonObject firstDispatch = client.awaitTask(3, TimeUnit.SECONDS);
            assertNotNull(firstDispatch, "manual callback replay worker should receive the task dispatch");
            client.sendSuccess(firstDispatch, "first-success");

            RuntimeTaskSnapshot terminalSnapshot = waitForTerminalRuntimeTask(taskId);
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminalSnapshot.task().get("terminalReason"));
            assertEquals(1, terminalSnapshot.stats().successCount());
            assertEquals(1, terminalSnapshot.stats().finalCount());
            assertTrue(terminalSnapshot.activeLeases().isEmpty());

            replayConflictingTaskResult(
                    taskId,
                    WsFrameTestSupport.messageId(firstDispatch),
                    "FAILED",
                    "replayed-conflict"
            );

            RuntimeTaskSnapshot afterReplay = waitForRuntimeTaskSnapshot(taskId,
                    snapshot -> "TERMINAL".equals(snapshot.task().get("status"))
                            && "ALL_MESSAGES_SUCCEEDED".equals(snapshot.task().get("terminalReason"))
                            && snapshot.stats().successCount() == 1
                            && snapshot.stats().failedCount() == 0
                            && snapshot.activeLeases().isEmpty(),
                    "TERMINAL runtime truth unchanged after duplicate replay",
                    20,
                    250L);
            assertEquals("ALL_MESSAGES_SUCCEEDED", afterReplay.task().get("terminalReason"));
            assertEquals(1, afterReplay.stats().successCount());
            assertEquals(0, afterReplay.stats().failedCount());
            assertEquals(1, afterReplay.stats().finalCount());

            TraceAnalyzeResponse trace = awaitTraceScenarioOk(
                    TRACE_OUTPUT_DIR,
                    "duplicate-callback-replay",
                    taskId
            );
            assertTrue(trace.eventTypeCounts().containsKey("CALLBACK_IGNORED_DUPLICATE"),
                    "canonical trace must suppress the replayed callback as duplicate");
        } finally {
            client.disconnect();
        }
    }

    private void replayConflictingTaskResult(String taskId, String messageId, String status, String detail) throws Exception {
        URI uri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        ReplayWebSocketClient client = new ReplayWebSocketClient(uri, "replay-worker");
        try {
            assertClientConnects(client, "replay WebSocket client failed to connect");
            client.sendMessage(WsFrameTestSupport.buildTaskResult(
                    messageId,
                    "demoApp",
                    "replay-worker",
                    taskId,
                    status,
                    detail
            ));
            client.awaitSilence(300, TimeUnit.MILLISECONDS);
        } finally {
            client.disconnect();
        }
    }

    private static final class ReplayWebSocketClient extends SampleWorkerWebSocketClient {
        private ReplayWebSocketClient(URI serverUri, String workerId) {
            super(serverUri, workerId);
        }

        @Override
        public void onMessage(String message) {
            super.onMessage(message);
        }

        private void awaitSilence(long timeout, TimeUnit unit) throws InterruptedException {
            unit.sleep(timeout);
        }
    }

}
