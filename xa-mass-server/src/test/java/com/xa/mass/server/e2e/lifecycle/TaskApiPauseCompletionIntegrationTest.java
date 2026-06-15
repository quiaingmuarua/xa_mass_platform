package com.xa.mass.server.e2e.lifecycle;

import com.google.gson.JsonObject;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.server.testutil.WsFrameTestSupport;
import com.xa.mass.workerpack.sample.client.SampleWorkerWebSocketClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.util.List;
import java.util.Map;
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
class TaskApiPauseCompletionIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void pausedRunningTaskClosesToTerminalWhenRealCallbacksArrive() throws Exception {
        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        ManualAckWebSocketClient firstClient = connectClientWithRetries(
                () -> new ManualAckWebSocketClient(wsUri, "us", "it-worker-0",
                        canonicalWorkerRouteKey("us", "it-worker-0")),
                "First sample worker failed to connect"
        );
        ManualAckWebSocketClient secondClient = connectClientWithRetries(
                () -> new ManualAckWebSocketClient(wsUri, "us", "it-worker-1",
                        canonicalWorkerRouteKey("us", "it-worker-1")),
                "Second sample worker failed to connect"
        );
        try {
            String taskId = createTaskId(
                    "pause-completion",
                    "pause completion integration",
                    List.of("target-a", "target-b"),
                    1
            );

            Map<String, Object> approveResponse = approveTask(taskId);
            assertApiOk(approveResponse);

            RuntimeTaskSnapshot runningSnapshot = waitForRuntimeTaskSnapshot(taskId, "RUNNING", 20, 250L);
            assertEquals(2, ((Number) runningSnapshot.task().get("peakAssignedWorkerCount")).intValue());
            assertEquals(2, runningSnapshot.stats().inflightCount());

            JsonObject firstDispatch = firstClient.awaitTask(3, TimeUnit.SECONDS);
            JsonObject secondDispatch = secondClient.awaitTask(3, TimeUnit.SECONDS);
            assertNotNull(firstDispatch);
            assertNotNull(secondDispatch);

            Map<String, Object> pauseResponse = pauseTask(taskId);
            assertApiOk(pauseResponse);

            RuntimeTaskSnapshot pausedSnapshot = waitForRuntimeTaskSnapshot(taskId, "PAUSED", 20, 250L);
            assertEquals("PAUSED", pausedSnapshot.task().get("status"));
            assertEquals(2, pausedSnapshot.stats().inflightCount());

            firstClient.sendResult(firstDispatch, "SUCCESS", "paused-complete-" + firstClient.getWorkerId());
            secondClient.sendResult(secondDispatch, "SUCCESS", "paused-complete-" + secondClient.getWorkerId());

            RuntimeTaskSnapshot terminalSnapshot = waitForRuntimeTaskSnapshot(taskId, "TERMINAL", 20, 250L);
            assertEquals("TERMINAL", terminalSnapshot.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminalSnapshot.task().get("terminalReason"));
            assertEquals(2, ((Number) terminalSnapshot.task().get("taskSuccessNumber")).intValue());
            assertEquals(2, terminalSnapshot.stats().totalCount());
            assertEquals(2, terminalSnapshot.stats().successCount());

            Map<String, Object> staleResumeResponse = resumeTask(taskId);
            assertApiError(staleResumeResponse, 409);
            assertEquals("TERMINAL", waitForRuntimeTaskSnapshot(taskId, "TERMINAL", 20, 250L).task().get("status"),
                    "Stale resume must not mutate terminal state");
        } finally {
            firstClient.disconnect();
            secondClient.disconnect();
        }
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
                    taskQueue.offer(frame);
                    return;
                }
            } catch (Exception ignored) {
                // Fall through to the base client for non-task frames.
            }
            super.onMessage(message);
        }

        private JsonObject awaitTask(long timeout, TimeUnit unit) throws InterruptedException {
            return taskQueue.poll(timeout, unit);
        }

        private void sendResult(JsonObject dispatchFrame, String status, String detail) throws Exception {
            sendMessage(WsFrameTestSupport.buildTaskResult(
                    WsFrameTestSupport.messageId(dispatchFrame),
                    WsFrameTestSupport.project(dispatchFrame),
                    getWorkerId(),
                    WsFrameTestSupport.taskId(dispatchFrame),
                    status,
                    detail
            ));
        }
    }
}
