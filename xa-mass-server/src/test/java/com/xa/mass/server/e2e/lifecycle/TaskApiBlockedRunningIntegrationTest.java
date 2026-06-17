package com.xa.mass.server.e2e.lifecycle;

import com.google.gson.JsonObject;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.server.testutil.WsFrameTestSupport;
import com.xa.mass.workerpack.sample.client.SampleWorkerWebSocketClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=false",
                "mass.mock.data.workers=mock/test_mock_workers.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("memory-local")
@DirtiesContext
class TaskApiBlockedRunningIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void blockedRunningTaskCompletesAfterReapproveAndWorkerCallbacks() throws Exception {
        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        ManualAckWebSocketClient firstClient = connectClientWithRetries(
                () -> new ManualAckWebSocketClient(wsUri, "us", "it-worker-0",
                        canonicalWorkerRouteKey("us", "it-worker-0")),
                "First worker failed to connect"
        );
        ManualAckWebSocketClient secondClient = connectClientWithRetries(
                () -> new ManualAckWebSocketClient(wsUri, "us", "it-worker-1",
                        canonicalWorkerRouteKey("us", "it-worker-1")),
                "Second worker failed to connect"
        );
        try {
            String taskId = createTaskId("blocked-running",
                    "blocked running e2e",
                    List.of("target-a", "target-b"), 1);

            assertApiOk(approveTask(taskId));

            RuntimeTaskSnapshot runningSnapshot = waitForRuntimeTaskSnapshot(taskId, "RUNNING", 20, 250L);
            assertEquals(2, ((Number) runningSnapshot.task().get("peakAssignedWorkerCount")).intValue());
            assertEquals(2, runningSnapshot.stats().inflightCount());

            JsonObject firstDispatch = firstClient.awaitTask(3, TimeUnit.SECONDS);
            JsonObject secondDispatch = secondClient.awaitTask(3, TimeUnit.SECONDS);
            assertNotNull(firstDispatch, "First worker did not receive a dispatch");
            assertNotNull(secondDispatch, "Second worker did not receive a dispatch");

            assertApiOk(blockTask(taskId));
            RuntimeTaskSnapshot blockedSnapshot = waitForRuntimeTaskSnapshot(taskId, "BLOCKED", 20, 250L);
            assertEquals("BLOCKED", blockedSnapshot.task().get("status"));
            assertEquals(2, blockedSnapshot.stats().inflightCount(),
                    "Blocking a running task must preserve in-flight runtime leases");

            assertApiOk(approveTask(taskId));
            RuntimeTaskSnapshot readySnapshot = waitForRuntimeTaskSnapshot(taskId, "READY", 20, 250L);
            assertEquals("READY", readySnapshot.task().get("status"));

            firstClient.sendResult(firstDispatch, "SUCCESS", "blocked-running-complete");
            secondClient.sendResult(secondDispatch, "SUCCESS", "blocked-running-complete");

            RuntimeTaskSnapshot terminalSnapshot = waitForTerminalRuntimeTask(taskId);
            assertEquals("TERMINAL", terminalSnapshot.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminalSnapshot.task().get("terminalReason"));
            assertEquals(2, ((Number) terminalSnapshot.task().get("taskSuccessNumber")).intValue());
            assertEquals(2, ((Number) terminalSnapshot.task().get("peakAssignedWorkerCount")).intValue());
            assertEquals(2, terminalSnapshot.stats().totalCount());
            assertEquals(2, terminalSnapshot.stats().successCount());
        } finally {
            firstClient.disconnect();
            secondClient.disconnect();
        }
    }

    private static final class ManualAckWebSocketClient extends SampleWorkerWebSocketClient {
        private final BlockingQueue<JsonObject> taskQueue = new LinkedBlockingQueue<>();

        ManualAckWebSocketClient(URI serverUri, String workerGroupId, String workerId, String routeKey) {
            super(com.xa.mass.server.e2e.support.AbstractSampleE2eTest.withWorkerRouteKey(serverUri, routeKey),
                    workerId,
                    workerGroupId);
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
                // Fall through to base handler.
            }
            super.onMessage(message);
        }

        JsonObject awaitTask(long timeout, TimeUnit unit) throws InterruptedException {
            return taskQueue.poll(timeout, unit);
        }

        void sendResult(JsonObject taskFrame, String status, String detail) throws Exception {
            sendMessage(WsFrameTestSupport.buildTaskResult(
                    WsFrameTestSupport.resultCorrelationRef(taskFrame),
                    status,
                    detail
            ));
        }
    }
}
